package pl.skyblock.miniony.manager;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;
import pl.skyblock.miniony.MinionyPlugin;
import pl.skyblock.miniony.config.PluginSettings;
import pl.skyblock.miniony.data.MinionSnapshot;
import pl.skyblock.miniony.hook.IslandHook;
import pl.skyblock.miniony.limit.LimitService;
import pl.skyblock.miniony.message.Messages;
import pl.skyblock.miniony.model.Minion;
import pl.skyblock.miniony.model.MinionState;
import pl.skyblock.miniony.model.MinionStatus;
import pl.skyblock.miniony.model.MinionStorage;
import pl.skyblock.miniony.type.MinionType;
import pl.skyblock.miniony.type.WorkContext;
import pl.skyblock.miniony.util.BlockPos;
import pl.skyblock.miniony.util.ChunkKeys;
import pl.skyblock.miniony.util.Drops;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Rejestr wszystkich minionów, indeksy (chunki, zajęte bloki, liczniki właścicieli),
 * stawianie/zbieranie, cykl pracy i zapis. Wszystkie metody - wyłącznie główny wątek.
 */
public final class MinionManager {

    public static final String PERM_ADMIN_ACCESS = "miniony.admin.access";

    private final MinionyPlugin plugin;
    private final Map<UUID, Minion> minions = new HashMap<>();
    /** świat -> klucz chunka -> miniony postawione w tym chunku */
    private final Map<String, Map<Long, List<Minion>>> chunkIndex = new HashMap<>();
    /** klucz bloku -> minion stojący w tym bloku */
    private final Map<String, Minion> occupied = new HashMap<>();
    /** właściciel -> liczba postawionych minionów */
    private final Map<UUID, Integer> placedCount = new HashMap<>();
    /** miniony z załadowanym chunkiem i postawionymi encjami */
    private final Set<Minion> active = new LinkedHashSet<>();
    /** chunki czekające na aktywację w następnym ticku */
    private final Set<String> pendingChunkActivation = new HashSet<>();

    private final WorkContext workContext = new WorkContext() {
        @Override
        public IslandHook islands() {
            return plugin.islands();
        }

        @Override
        public boolean isMinionAt(World world, int x, int y, int z) {
            return occupied.containsKey(ChunkKeys.blockKey(world.getName(), x, y, z));
        }

        @Override
        public boolean isMinionItem(ItemStack item) {
            return plugin.items().isMinionItem(item);
        }

        @Override
        public boolean kill(Minion minion, LivingEntity entity, double dropMultiplier, boolean dropExp) {
            return killForMinion(minion, entity, dropMultiplier, dropExp);
        }
    };

    /** Zabójstwo w toku - EntityDeathEvent jest wywoływany synchronicznie wewnątrz setHealth(0). */
    private record PendingKill(Minion minion, UUID entity, double multiplier, boolean dropExp) {
    }

    private PendingKill pendingKill;

    private BukkitTask workTask;
    private BukkitTask saveTask;
    private final Set<String> reportedWorkErrors = new HashSet<>();

    public MinionManager(MinionyPlugin plugin) {
        this.plugin = plugin;
    }

    // =====================================================================================
    // Cykl życia
    // =====================================================================================

    /** Wczytuje miniony z bazy (główny wątek, przy starcie). */
    public void load(Collection<MinionSnapshot> snapshots) {
        int placed = 0;
        for (MinionSnapshot s : snapshots) {
            Minion minion = fromSnapshot(s);
            minions.put(minion.id(), minion);
            if (minion.state() == MinionState.PLACED) {
                if (minion.world() == null) {
                    plugin.getLogger().warning("Minion " + minion.id() + " jest oznaczony jako postawiony, ale nie ma lokalizacji - zwracam go właścicielowi.");
                    minion.setState(MinionState.ITEM);
                    minion.setPendingDelivery(true);
                    minion.rotateToken();
                    minion.setDirty(true);
                    continue;
                }
                if (occupied.containsKey(minion.blockKey())) {
                    plugin.getLogger().warning("Dwa miniony w tym samym bloku (" + minion.blockKey() + ") - minion " + minion.id() + " wraca do właściciela.");
                    minion.setState(MinionState.ITEM);
                    minion.clearLocation();
                    minion.setPendingDelivery(true);
                    minion.rotateToken();
                    minion.setDirty(true);
                    continue;
                }
                index(minion);
                placed++;
            }
        }
        plugin.getLogger().info("Wczytano " + minions.size() + " minionów (postawionych: " + placed + ").");
    }

    private Minion fromSnapshot(MinionSnapshot s) {
        MinionType type = plugin.types().get(s.type());
        int capacity = type != null ? type.settings().level(Math.max(1, s.level())).capacity() : 9;
        ItemStack[] items = null;
        if (s.storage() != null && s.storage().length > 0) {
            try {
                items = ItemStack.deserializeItemsFromBytes(s.storage());
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Nie udało się odczytać magazynu miniona " + s.id()
                        + " - magazyn będzie pusty.", e);
            }
        }
        MinionStorage storage = MinionStorage.of(items, capacity);
        int level = Math.max(1, s.level());
        if (type != null && level > type.settings().maxLevel()) {
            plugin.getLogger().warning("Minion " + s.id() + " ma poziom " + level + ", a typ '" + type.id() + "' ma tylko "
                    + type.settings().maxLevel() + " poziomów - obniżam do maksymalnego.");
            level = type.settings().maxLevel();
        }
        Minion m = new Minion(s.id(), s.owner(), s.ownerName(), s.type(),
                s.rarity() == null ? PluginSettings.DEFAULT_RARITY : s.rarity(), level, storage, s.createdAt());
        if (level != s.level()) {
            m.setDirty(true);
        }
        m.setIslandId(s.island());
        m.setToken(s.token());
        MinionState state;
        try {
            state = MinionState.valueOf(s.state());
        } catch (IllegalArgumentException | NullPointerException e) {
            state = MinionState.ITEM;
        }
        m.setState(state);
        if (s.world() != null) {
            m.setLocation(s.world(), s.x(), s.y(), s.z(), s.yaw());
        }
        if (s.modules() != null && !s.modules().isBlank()) {
            for (String module : s.modules().split(",")) {
                if (!module.isBlank()) {
                    m.modules().add(module.trim());
                }
            }
        }
        m.setPendingDelivery(s.pendingDelivery());
        m.setLastWorkAt(s.lastWorkAt());
        return m;
    }

    /** Uruchamia zadania, usuwa osierocone encje i aktywuje miniony w załadowanych chunkach. */
    public void start() {
        for (World world : Bukkit.getWorlds()) {
            removeOrphans(world.getEntities());
            activateWorld(world);
        }
        startTasks();
        flushDirty();
    }

    private void startTasks() {
        stopTasks();
        PluginSettings settings = plugin.settings();
        long period = settings.workTickPeriod();
        workTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, period, period);
        long save = settings.autosaveSeconds() * 20L;
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::flushDirty, save, save);
    }

    private void stopTasks() {
        if (workTask != null) {
            workTask.cancel();
            workTask = null;
        }
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
    }

    /** Wyłączenie pluginu: zatrzymuje zadania, usuwa encje i zapisuje wszystkie zmiany. */
    public void shutdown() {
        stopTasks();
        for (Minion minion : new ArrayList<>(active)) {
            deactivate(minion);
        }
        active.clear();
        pendingChunkActivation.clear();
        flushDirty();
    }

    /** Po przeładowaniu configu: przelicza obszary, pojemności, odtwarza encje i restartuje zadania. */
    public void reload() {
        List<Minion> wasActive = new ArrayList<>(active);
        for (Minion minion : wasActive) {
            deactivate(minion);
        }
        for (Minion minion : minions.values()) {
            minion.setArea(null);
            MinionType type = plugin.types().get(minion.typeId());
            if (type != null && minion.level() > type.settings().maxLevel()) {
                plugin.getLogger().warning("Minion " + minion.id() + " ma poziom " + minion.level() + ", a typ '" + type.id()
                        + "' ma teraz " + type.settings().maxLevel() + " poziomów - obniżam do maksymalnego.");
                minion.setLevel(type.settings().maxLevel());
                minion.setDirty(true);
            }
            if (type != null && minion.storage().capacity() != type.level(minion).capacity()) {
                minion.storage().setCapacity(type.level(minion).capacity());
                minion.setDirty(true);
            }
        }
        for (Minion minion : wasActive) {
            World world = Bukkit.getWorld(minion.world());
            if (world != null) {
                activate(minion, world);
            }
        }
        for (World world : Bukkit.getWorlds()) {
            activateWorld(world);
        }
        startTasks();
    }

    // =====================================================================================
    // Zapytania
    // =====================================================================================

    public Minion get(UUID id) {
        return id == null ? null : minions.get(id);
    }

    public int placedCount(UUID owner) {
        return placedCount.getOrDefault(owner, 0);
    }

    public List<Minion> placedBy(UUID owner) {
        List<Minion> result = new ArrayList<>();
        for (Minion m : minions.values()) {
            if (m.state() == MinionState.PLACED && m.owner().equals(owner)) {
                result.add(m);
            }
        }
        result.sort(Comparator.comparingLong(Minion::createdAt));
        return result;
    }

    public int pendingCount(UUID owner) {
        int count = 0;
        for (Minion m : minions.values()) {
            if (m.pendingDelivery() && m.state() == MinionState.ITEM && m.owner().equals(owner)) {
                count++;
            }
        }
        return count;
    }

    public boolean canAccess(Player player, Minion minion) {
        if (player.getUniqueId().equals(minion.owner()) || player.hasPermission(PERM_ADMIN_ACCESS)) {
            return true;
        }
        IslandHook islands = plugin.islands();
        return plugin.settings().memberAccess() && islands.isEnabled() && minion.islandId() != null
                && islands.isMember(player, minion.islandId());
    }

    public boolean canPickup(Player player, Minion minion) {
        return player.getUniqueId().equals(minion.owner()) || player.hasPermission(PERM_ADMIN_ACCESS);
    }

    // =====================================================================================
    // Indeksy
    // =====================================================================================

    private void index(Minion minion) {
        chunkIndex.computeIfAbsent(minion.world(), w -> new HashMap<>())
                .computeIfAbsent(minion.chunkKey(), k -> new ArrayList<>(2))
                .add(minion);
        occupied.put(minion.blockKey(), minion);
        placedCount.merge(minion.owner(), 1, Integer::sum);
    }

    private void unindex(Minion minion) {
        Map<Long, List<Minion>> worldMap = chunkIndex.get(minion.world());
        if (worldMap != null) {
            List<Minion> list = worldMap.get(minion.chunkKey());
            if (list != null) {
                list.remove(minion);
                if (list.isEmpty()) {
                    worldMap.remove(minion.chunkKey());
                }
            }
            if (worldMap.isEmpty()) {
                chunkIndex.remove(minion.world());
            }
        }
        occupied.remove(minion.blockKey(), minion);
        placedCount.computeIfPresent(minion.owner(), (k, v) -> v <= 1 ? null : v - 1);
    }

    // =====================================================================================
    // Aktywacja (chunki)
    // =====================================================================================

    private void activateWorld(World world) {
        Map<Long, List<Minion>> worldMap = chunkIndex.get(world.getName());
        if (worldMap == null) {
            return;
        }
        for (List<Minion> list : worldMap.values()) {
            for (Minion minion : list) {
                if (!active.contains(minion) && world.isChunkLoaded(minion.chunkX(), minion.chunkZ())) {
                    activate(minion, world);
                }
            }
        }
    }

    private void activate(Minion minion, World world) {
        if (minion.state() != MinionState.PLACED || !world.isChunkLoaded(minion.chunkX(), minion.chunkZ())) {
            return;
        }
        if (minion.area() == null) {
            minion.setArea(computeArea(minion, world));
        }
        plugin.visuals().spawn(minion, world);
        active.add(minion);
        MinionType type = plugin.types().get(minion.typeId());
        long interval = type != null ? type.level(minion).intervalSeconds() * 1000L : 5000L;
        // rozłożenie pracy w czasie, żeby miniony z jednego chunka nie pracowały w tym samym ticku
        minion.setNextWorkAt(System.currentTimeMillis() + ThreadLocalRandom.current().nextLong(Math.max(1, interval)));
    }

    private void deactivate(Minion minion) {
        plugin.visuals().despawn(minion);
        active.remove(minion);
        minion.setStatus(MinionStatus.IDLE);
    }

    private List<BlockPos> computeArea(Minion minion, World world) {
        MinionType type = plugin.types().get(minion.typeId());
        if (type == null || !type.usesBlockArea()) {
            return List.of();
        }
        int range = type.level(minion).range();
        int y = minion.blockY() + type.areaYOffset();
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return List.of();
        }
        IslandHook islands = plugin.islands();
        boolean checkIsland = islands.isEnabled() && minion.islandId() != null;
        List<BlockPos> area = new ArrayList<>((2 * range + 1) * (2 * range + 1));
        Location probe = new Location(world, 0, y, 0);
        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                if (dx == 0 && dz == 0) {
                    continue; // blok, na którym stoi minion
                }
                int x = minion.blockX() + dx;
                int z = minion.blockZ() + dz;
                if (checkIsland) {
                    probe.set(x + 0.5, y, z + 0.5);
                    if (!islands.isInsideIsland(minion.islandId(), probe)) {
                        continue;
                    }
                }
                area.add(new BlockPos(x, y, z));
            }
        }
        return List.copyOf(area);
    }

    public void onChunkLoad(Chunk chunk) {
        Map<Long, List<Minion>> worldMap = chunkIndex.get(chunk.getWorld().getName());
        if (worldMap == null || !worldMap.containsKey(ChunkKeys.chunkKey(chunk.getX(), chunk.getZ()))) {
            return;
        }
        String key = chunk.getWorld().getName() + ';' + chunk.getX() + ';' + chunk.getZ();
        if (!pendingChunkActivation.add(key)) {
            return;
        }
        String worldName = chunk.getWorld().getName();
        int cx = chunk.getX();
        int cz = chunk.getZ();
        // encje tworzymy tick później - nie w trakcie ładowania chunka
        Bukkit.getScheduler().runTask(plugin, () -> {
            pendingChunkActivation.remove(key);
            World world = Bukkit.getWorld(worldName);
            if (world == null || !world.isChunkLoaded(cx, cz)) {
                return;
            }
            Map<Long, List<Minion>> map = chunkIndex.get(worldName);
            List<Minion> list = map == null ? null : map.get(ChunkKeys.chunkKey(cx, cz));
            if (list == null) {
                return;
            }
            for (Minion minion : new ArrayList<>(list)) {
                if (!active.contains(minion)) {
                    activate(minion, world);
                }
            }
        });
    }

    public void onChunkUnload(Chunk chunk) {
        Map<Long, List<Minion>> worldMap = chunkIndex.get(chunk.getWorld().getName());
        if (worldMap == null) {
            return;
        }
        List<Minion> list = worldMap.get(ChunkKeys.chunkKey(chunk.getX(), chunk.getZ()));
        if (list == null) {
            return;
        }
        for (Minion minion : new ArrayList<>(list)) {
            if (active.contains(minion)) {
                plugin.gui().closeAll(minion.id());
                deactivate(minion);
            }
        }
    }

    public void onWorldLoad(World world) {
        activateWorld(world);
    }

    public void onWorldUnload(World world) {
        for (Minion minion : new ArrayList<>(active)) {
            if (world.getName().equals(minion.world())) {
                plugin.gui().closeAll(minion.id());
                deactivate(minion);
            }
        }
    }

    /** Usuwa encje minionów, które nie są aktualnie śledzone (pozostałości po crashu/starej wersji). */
    public void removeOrphans(Collection<? extends Entity> entities) {
        for (Entity entity : entities) {
            UUID id = plugin.visuals().minionIdOf(entity);
            if (id == null) {
                continue;
            }
            Minion minion = minions.get(id);
            if (minion == null || !plugin.visuals().isTracked(minion, entity)) {
                entity.remove();
            }
        }
    }

    // =====================================================================================
    // Praca
    // =====================================================================================

    private void tick() {
        if (active.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Minion minion : new ArrayList<>(active)) {
            World world = Bukkit.getWorld(minion.world());
            if (world == null || !world.isChunkLoaded(minion.chunkX(), minion.chunkZ())) {
                deactivate(minion);
                continue;
            }
            if (!plugin.visuals().isIntact(minion)) {
                plugin.visuals().spawn(minion, world);
            } else {
                keepInPlace(minion, world);
            }
            if (now < minion.nextWorkAt()) {
                continue;
            }
            MinionType type = plugin.types().get(minion.typeId());
            if (type == null || !type.isEnabled()) {
                minion.setNextWorkAt(now + 5000L);
                minion.setStatus(MinionStatus.IDLE);
                plugin.visuals().updateHologram(minion);
                continue;
            }
            minion.setNextWorkAt(now + type.level(minion).intervalSeconds() * 1000L);
            int before = minion.storage().totalItems();
            MinionStatus status;
            try {
                status = type.work(minion, world, workContext);
            } catch (RuntimeException e) {
                status = MinionStatus.IDLE;
                if (reportedWorkErrors.add(type.id())) {
                    plugin.getLogger().log(Level.SEVERE, "Błąd pracy miniona typu '" + type.id() + "' (" + minion.id() + ")", e);
                }
            }
            minion.setStatus(status);
            if (status == MinionStatus.WORKING) {
                minion.setLastWorkAt(now);
            }
            if (minion.storage().totalItems() != before) {
                minion.setDirty(true);
                plugin.gui().refresh(minion.id());
            }
            plugin.visuals().updateHologram(minion);
        }
    }

    private void keepInPlace(Minion minion, World world) {
        ArmorStand body = minion.body();
        if (body == null) {
            return;
        }
        Location current = body.getLocation();
        double dx = current.getX() - minion.x();
        double dy = current.getY() - minion.y();
        double dz = current.getZ() - minion.z();
        if (dx * dx + dy * dy + dz * dz > 0.01) {
            body.teleport(plugin.visuals().baseLocation(minion, world));
        }
    }

    private boolean killForMinion(Minion minion, LivingEntity entity, double multiplier, boolean dropExp) {
        if (pendingKill != null || entity.isDead()) {
            return false;
        }
        pendingKill = new PendingKill(minion, entity.getUniqueId(), multiplier, dropExp);
        try {
            entity.setHealth(0);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Nie udało się zabić moba " + entity.getType() + " przez miniona " + minion.id(), e);
            return false;
        } finally {
            pendingKill = null;
        }
        return entity.isDead();
    }

    /**
     * Przechwycenie dropu moba zabitego przez Zabójcę. Drop (z mnożnikiem) trafia do magazynu,
     * a to, co się nie zmieściło, spada normalnie na ziemię.
     */
    public void onEntityDeath(EntityDeathEvent event) {
        PendingKill kill = pendingKill;
        if (kill == null || !kill.entity().equals(event.getEntity().getUniqueId())) {
            return;
        }
        List<ItemStack> leftovers = new ArrayList<>();
        for (ItemStack stack : Drops.multiply(event.getDrops(), kill.multiplier())) {
            int left = kill.minion().storage().addPartial(stack);
            if (left > 0) {
                ItemStack rest = stack.clone();
                rest.setAmount(left);
                leftovers.add(rest);
            }
        }
        event.getDrops().clear();
        event.getDrops().addAll(leftovers);
        if (!kill.dropExp()) {
            event.setDroppedExp(0);
        }
    }

    /** Po zmianie poziomu: nowa pojemność, obszar, wygląd i hologram. */
    public void applyLevelChange(Minion minion) {
        MinionType type = plugin.types().get(minion.typeId());
        if (type == null) {
            return;
        }
        minion.storage().setCapacity(type.level(minion).capacity());
        minion.setArea(null);
        if (active.contains(minion)) {
            World world = Bukkit.getWorld(minion.world());
            if (world != null) {
                minion.setArea(computeArea(minion, world));
                plugin.visuals().spawn(minion, world);
            }
        }
    }

    // =====================================================================================
    // Tworzenie / stawianie / zbieranie
    // =====================================================================================

    /** Tworzy nowego miniona w stanie przedmiotu i od razu zapisuje go w bazie. */
    public Minion createItemMinion(MinionType type, Player owner, int level) {
        int lvl = Math.max(1, Math.min(level, type.settings().maxLevel()));
        Minion minion = new Minion(UUID.randomUUID(), owner.getUniqueId(), owner.getName(), type.id(),
                PluginSettings.DEFAULT_RARITY, lvl, new MinionStorage(type.settings().level(lvl).capacity()),
                System.currentTimeMillis());
        minion.setState(MinionState.ITEM);
        minions.put(minion.id(), minion);
        saveNow(minion);
        return minion;
    }

    /**
     * Próba postawienia miniona z przedmiotu. Wywoływana z PlayerInteractEvent (zdarzenie jest już anulowane).
     */
    public void tryPlace(Player player, EquipmentSlot hand, ItemStack item, Block target) {
        Messages msg = plugin.messages();
        MinionItems items = plugin.items();
        UUID id = items.readId(item);
        UUID token = items.readToken(item);
        Minion minion = get(id);
        if (minion == null || token == null) {
            removeHandItem(player, hand, id);
            msg.send(player, "nieprawidlowy-item");
            plugin.getLogger().warning("Gracz " + player.getName() + " użył nieważnego przedmiotu miniona (id=" + id + ") - usunięto.");
            return;
        }
        if (minion.state() == MinionState.PLACED) {
            removeHandItem(player, hand, id);
            msg.send(player, "minion-juz-postawiony");
            plugin.getLogger().warning("Gracz " + player.getName() + " użył kopii przedmiotu postawionego miniona " + id + " - usunięto.");
            return;
        }
        if (!token.equals(minion.token())) {
            removeHandItem(player, hand, id);
            msg.send(player, "nieprawidlowy-item");
            plugin.getLogger().warning("Gracz " + player.getName() + " użył nieaktualnej kopii przedmiotu miniona " + id + " - usunięto.");
            return;
        }
        MinionType type = plugin.types().get(minion.typeId());
        if (type == null || !type.isEnabled()) {
            msg.send(player, "typ-wylaczony");
            return;
        }
        World world = target.getWorld();
        if (!(target.getType().isAir() || target.isReplaceable()) || !target.getRelative(0, 1, 0).isPassable()
                || target.getY() - 1 < world.getMinHeight()) {
            msg.send(player, "zajete-miejsce");
            return;
        }
        String blockKey = ChunkKeys.blockKey(world.getName(), target.getX(), target.getY(), target.getZ());
        if (occupied.containsKey(blockKey)) {
            msg.send(player, "za-blisko");
            return;
        }
        Location location = target.getLocation().add(0.5, 0, 0.5);
        IslandHook islands = plugin.islands();
        UUID islandId = null;
        if (islands.isEnabled()) {
            islandId = islands.islandAt(location);
            boolean admin = player.hasPermission(PERM_ADMIN_ACCESS);
            if (plugin.settings().requireIsland() && !admin
                    && (islandId == null || !islands.isMember(player, islandId))) {
                msg.send(player, "nie-twoja-wyspa");
                return;
            }
        }
        int limit = plugin.limits().limit(player);
        int count = placedCount(player.getUniqueId());
        if (count >= limit) {
            msg.send(player, "limit-osiagniety", Placeholder.unparsed("liczba", String.valueOf(count)),
                    Placeholder.component("limit", limitComponent(limit)));
            return;
        }
        // Pobranie przedmiotu - weryfikujemy, że w ręce nadal jest ten sam minion z tym samym tokenem.
        PlayerInventory inventory = player.getInventory();
        ItemStack inHand = inventory.getItem(hand);
        if (!id.equals(items.readId(inHand)) || !token.equals(items.readToken(inHand))) {
            return;
        }
        if (inHand.getAmount() > 1) {
            inHand.setAmount(inHand.getAmount() - 1);
            inventory.setItem(hand, inHand);
        } else {
            inventory.setItem(hand, null);
        }

        float yaw = normalizeYaw(player.getLocation().getYaw() + 180f);
        minion.setOwner(player.getUniqueId(), player.getName());
        minion.setIslandId(islandId);
        minion.setLocation(world.getName(), location.getX(), location.getY(), location.getZ(), yaw);
        minion.setState(MinionState.PLACED);
        minion.setPendingDelivery(false);
        minion.rotateToken();
        minion.setStatus(MinionStatus.IDLE);
        minion.setLastWorkAt(System.currentTimeMillis());
        index(minion);
        saveNow(minion);
        activate(minion, world);
        msg.send(player, "postawiono", MinionPlaceholders.of(plugin, minion),
                Placeholder.unparsed("liczba", String.valueOf(count + 1)),
                Placeholder.component("limit", limitComponent(limit)));
    }

    private static float normalizeYaw(float yaw) {
        yaw %= 360f;
        if (yaw < 0) {
            yaw += 360f;
        }
        return Math.round(yaw / 45f) * 45f;
    }

    private void removeHandItem(Player player, EquipmentSlot hand, UUID expectedId) {
        ItemStack inHand = player.getInventory().getItem(hand);
        if (expectedId == null ? plugin.items().isMinionItem(inHand) : expectedId.equals(plugin.items().readId(inHand))) {
            player.getInventory().setItem(hand, null);
        }
    }

    public net.kyori.adventure.text.Component limitComponent(int limit) {
        return limit == LimitService.UNLIMITED
                ? plugin.messages().component("limit-bez-limitu")
                : net.kyori.adventure.text.Component.text(limit);
    }

    /** Zbiera miniona do ekwipunku gracza. */
    public void pickup(Player player, Minion minion) {
        Messages msg = plugin.messages();
        if (minion.state() != MinionState.PLACED || minions.get(minion.id()) != minion) {
            msg.send(player, "minion-niedostepny");
            return;
        }
        if (!canPickup(player, minion)) {
            msg.send(player, "tylko-wlasciciel-zbiera");
            return;
        }
        PlayerInventory inventory = player.getInventory();
        int slot = inventory.firstEmpty();
        if (slot < 0) {
            msg.send(player, "brak-miejsca-ekwipunek");
            return;
        }
        TagResolver placeholders = MinionPlaceholders.of(plugin, minion);
        plugin.gui().closeAll(minion.id());
        deactivate(minion);
        unindex(minion);
        minion.setState(MinionState.ITEM);
        minion.clearLocation();
        minion.setIslandId(null);
        minion.setPendingDelivery(false);
        minion.rotateToken();
        // Najpierw przedmiot miniona (gwarantowany wolny slot), potem zawartość magazynu.
        inventory.setItem(slot, plugin.items().create(minion));
        boolean partial = false;
        if (plugin.settings().pickupReturnsStorage()) {
            partial = transferStorage(player, minion) > 0;
            inventory.setItem(slot, plugin.items().create(minion)); // aktualne lore (zapełnienie magazynu)
        }
        saveNowThenSavePlayer(minion, player);
        msg.send(player, "zebrano", placeholders);
        if (partial) {
            msg.send(player, "zebrano-czesc-magazynu");
        }
    }

    /**
     * Przenosi zawartość magazynu do ekwipunku gracza.
     * Zwraca liczbę sztuk, które zostały w magazynie (brak miejsca).
     */
    public int transferStorage(Player player, Minion minion) {
        MinionStorage storage = minion.storage();
        int left = 0;
        for (int i = 0; i < storage.size(); i++) {
            ItemStack stored = storage.get(i);
            if (stored == null) {
                continue;
            }
            int remaining = giveToPlayer(player, stored);
            if (remaining <= 0) {
                storage.set(i, null);
            } else {
                ItemStack rest = stored.clone();
                rest.setAmount(remaining);
                storage.set(i, rest);
                left += remaining;
            }
        }
        minion.setDirty(true);
        return left;
    }

    /** Daje graczowi kopię przedmiotu, zwraca liczbę sztuk, które się nie zmieściły. */
    public int giveToPlayer(Player player, ItemStack stack) {
        Map<Integer, ItemStack> rest = player.getInventory().addItem(stack.clone());
        int remaining = 0;
        for (ItemStack r : rest.values()) {
            remaining += r.getAmount();
        }
        return remaining;
    }

    /**
     * Zwraca miniony z wyspy właścicielom (usunięcie wyspy, wyrzucenie lub opuszczenie wyspy).
     * ownerFilter == null - wszystkie miniony z wyspy.
     */
    public void returnIslandMinions(UUID islandId, UUID ownerFilter) {
        if (islandId == null) {
            return;
        }
        Set<UUID> owners = new HashSet<>();
        for (Minion minion : new ArrayList<>(minions.values())) {
            if (minion.state() != MinionState.PLACED || !islandId.equals(minion.islandId())) {
                continue;
            }
            if (ownerFilter != null && !ownerFilter.equals(minion.owner())) {
                continue;
            }
            plugin.gui().closeAll(minion.id());
            deactivate(minion);
            unindex(minion);
            minion.setState(MinionState.ITEM);
            minion.clearLocation();
            minion.setIslandId(null);
            minion.setPendingDelivery(true);
            minion.rotateToken();
            saveNow(minion);
            owners.add(minion.owner());
        }
        if (owners.isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (UUID owner : owners) {
                Player player = Bukkit.getPlayer(owner);
                if (player != null) {
                    deliverPending(player, false);
                }
            }
        });
    }

    /** Wydaje graczowi miniony oczekujące na zwrot (tyle, ile zmieści się w ekwipunku). */
    public void deliverPending(Player player, boolean feedbackIfNone) {
        List<Minion> pending = new ArrayList<>();
        for (Minion minion : minions.values()) {
            if (minion.pendingDelivery() && minion.state() == MinionState.ITEM && minion.owner().equals(player.getUniqueId())) {
                pending.add(minion);
            }
        }
        if (pending.isEmpty()) {
            if (feedbackIfNone) {
                plugin.messages().send(player, "brak-do-odebrania");
            }
            return;
        }
        int delivered = 0;
        PlayerInventory inventory = player.getInventory();
        for (Minion minion : pending) {
            int slot = inventory.firstEmpty();
            if (slot < 0) {
                break;
            }
            minion.rotateToken();
            minion.setPendingDelivery(false);
            inventory.setItem(slot, plugin.items().create(minion));
            saveNowThenSavePlayer(minion, player);
            delivered++;
        }
        if (delivered > 0) {
            plugin.messages().send(player, "zwrocono-miniony", Placeholder.unparsed("ilosc", String.valueOf(delivered)));
        }
        int remaining = pending.size() - delivered;
        if (remaining > 0) {
            plugin.messages().send(player, "ekwipunek-pelny-zwrot", Placeholder.unparsed("ilosc", String.valueOf(remaining)));
        }
    }

    /** Aktualizuje nick właściciela (hologram, lista) po wejściu gracza. */
    public void updateOwnerName(Player player) {
        for (Minion minion : minions.values()) {
            if (minion.owner().equals(player.getUniqueId()) && !player.getName().equals(minion.ownerName())) {
                minion.setOwnerName(player.getName());
                minion.setDirty(true);
                if (active.contains(minion)) {
                    plugin.visuals().updateHologram(minion);
                }
            }
        }
    }

    /** Wywoływane po zmianie magazynu przez gracza (GUI) - natychmiastowy zapis i odświeżenie widoków. */
    public void storageChangedByPlayer(Minion minion) {
        saveNow(minion);
        if (active.contains(minion)) {
            plugin.visuals().updateHologram(minion);
        }
        plugin.gui().refresh(minion.id());
    }

    // =====================================================================================
    // Zapis
    // =====================================================================================

    public MinionSnapshot snapshot(Minion m) {
        byte[] storage = ItemStack.serializeItemsAsBytes(m.storage().snapshot());
        return new MinionSnapshot(m.id(), m.owner(), m.ownerName(), m.islandId(), m.typeId(), m.rarity(), m.level(),
                m.state().name(), m.token(), m.world(), m.x(), m.y(), m.z(), m.yaw(), storage,
                String.join(",", m.modules()), m.pendingDelivery(), m.createdAt(), m.lastWorkAt());
    }

    /** Natychmiastowy (asynchroniczny) zapis jednego miniona. */
    public void saveNow(Minion minion) {
        minion.setDirty(false);
        plugin.database().saveAsync(List.of(snapshot(minion)), this::onSaveFailure);
    }

    /**
     * Zapis miniona, a PO potwierdzonym zapisie w bazie - zapis danych gracza (ekwipunku).
     * Kolejność jest celowa: przy crashu w trakcie gracz może co najwyżej stracić przedmiot,
     * ale nigdy nie powstanie duplikat (baza zawsze jest zapisana pierwsza).
     */
    private void saveNowThenSavePlayer(Minion minion, Player player) {
        minion.setDirty(false);
        UUID playerId = player.getUniqueId();
        plugin.database().saveAsync(List.of(snapshot(minion)), this::onSaveFailure, () -> {
            if (!plugin.isEnabled()) {
                return;
            }
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player online = Bukkit.getPlayer(playerId);
                    if (online != null) {
                        online.saveData();
                    }
                });
            } catch (RuntimeException ignored) {
                // plugin w trakcie wyłączania - dane gracza zapisze serwer
            }
        });
    }

    /** Zapisuje wszystkie zmienione miniony (snapshot na głównym wątku, zapis asynchronicznie). */
    public void flushDirty() {
        List<MinionSnapshot> snapshots = new ArrayList<>();
        for (Minion minion : minions.values()) {
            if (minion.isDirty()) {
                minion.setDirty(false);
                snapshots.add(snapshot(minion));
            }
        }
        plugin.database().saveAsync(snapshots, this::onSaveFailure);
    }

    private void onSaveFailure(Collection<MinionSnapshot> failed) {
        // wywoływane na wątku bazy - oznaczenie do ponownego zapisu musi wrócić na główny wątek
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (MinionSnapshot s : failed) {
                    Minion minion = minions.get(s.id());
                    if (minion != null) {
                        minion.setDirty(true);
                    }
                }
            });
        } catch (RuntimeException ignored) {
            // plugin w trakcie wyłączania (IllegalPluginAccessException)
        }
    }
}
