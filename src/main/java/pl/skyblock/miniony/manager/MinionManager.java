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
import pl.skyblock.miniony.config.LevelSettings;
import pl.skyblock.miniony.config.PluginSettings;
import pl.skyblock.miniony.config.RaritySettings;
import pl.skyblock.miniony.config.TypeSettings;
import pl.skyblock.miniony.data.MinionSnapshot;
import pl.skyblock.miniony.hook.IslandHook;
import pl.skyblock.miniony.limit.LimitService;
import pl.skyblock.miniony.message.Messages;
import pl.skyblock.miniony.model.EffectiveStats;
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
    private final Map<String, Map<Long, Minion>> occupied = new HashMap<>();
    /** właściciel -> liczba postawionych minionów */
    private final Map<UUID, Integer> placedCount = new HashMap<>();
    /** właściciel -> wszystkie jego miniony (w każdym stanie) */
    private final Map<UUID, Set<Minion>> byOwner = new HashMap<>();
    /** miniony z załadowanym chunkiem i postawionymi encjami */
    private final Set<Minion> active = new LinkedHashSet<>();
    /** bufor wielokrotnego użytku dla taktu pracy (bez alokacji co takt) */
    private final List<Minion> tickBuffer = new ArrayList<>();
    /** pozycja, od której zaczyna się kolejny takt (gdy poprzedni przekroczył budżet czasu) */
    private int tickCursor;
    private long nextHealthCheckAt;
    private static final long HEALTH_CHECK_INTERVAL_MS = 5000L;
    /** chunki czekające na aktywację w następnym ticku */
    private final Set<String> pendingChunkActivation = new HashSet<>();

    private final WorkContext workContext = new WorkContext() {
        @Override
        public IslandHook islands() {
            return plugin.islands();
        }

        @Override
        public boolean isMinionAt(World world, int x, int y, int z) {
            return occupiedAt(world.getName(), x, y, z) != null;
        }

        @Override
        public boolean isMinionItem(ItemStack item) {
            return plugin.items().isMinionItem(item);
        }

        @Override
        public boolean kill(Minion minion, LivingEntity entity, boolean dropExp) {
            return killForMinion(minion, entity, dropExp);
        }

        @Override
        public EffectiveStats stats(Minion minion) {
            return MinionManager.this.stats(minion);
        }

        @Override
        public List<ItemStack> processDrops(Minion minion, Collection<ItemStack> drops) {
            return plugin.modules().processDrops(minion, drops);
        }
    };

    /** Zabójstwo w toku - EntityDeathEvent jest wywoływany synchronicznie wewnątrz setHealth(0). */
    private record PendingKill(Minion minion, UUID entity, boolean dropExp) {
    }

    /** Miniony do zapisania na koniec bieżącego taktu (sprzedaż, przeniesienie do skrzyni). */
    private final Set<Minion> urgentSave = new LinkedHashSet<>();
    private long lastTickAt;

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

    /**
     * Wczytuje miniony z bazy (główny wątek, przy starcie).
     *
     * @param heartbeat ostatni zapisany "znak życia" serwera (0 = brak) - do rozliczenia pracy offline po restarcie/crashu
     */
    public void load(Collection<MinionSnapshot> snapshots, long heartbeat) {
        int placed = 0;
        long now = System.currentTimeMillis();
        boolean countDowntime = plugin.settings().offline().countServerDowntime();
        for (MinionSnapshot s : snapshots) {
            Minion minion = fromSnapshot(s);
            minions.put(minion.id(), minion);
            addOwner(minion);
            if (minion.state() == MinionState.PLACED) {
                // Rozliczenie czasu offline po restarcie:
                // - minion aktywny w chwili wyłączenia/crashu (lastActiveAt == 0) liczy od ostatniego heartbeat
                //   (albo od teraz, gdy czas wyłączenia serwera się nie liczy),
                // - minion nieaktywny przesuwa znacznik o czas wyłączenia serwera (gdy się nie liczy).
                if (minion.lastActiveAt() == Minion.NO_OFFLINE) {
                    // minion nie pracował w chwili dezaktywacji - bez kredytu offline
                } else if (heartbeat <= 0 || heartbeat > now) {
                    if (minion.lastActiveAt() <= 0 || minion.lastActiveAt() > now) {
                        minion.setLastActiveAt(now);
                    }
                } else if (minion.lastActiveAt() <= 0) {
                    minion.setLastActiveAt(countDowntime ? heartbeat : now);
                } else if (!countDowntime) {
                    minion.setLastActiveAt(Math.min(now, minion.lastActiveAt() + (now - heartbeat)));
                }
                if (minion.world() == null) {
                    plugin.getLogger().warning("Minion " + minion.id() + " jest oznaczony jako postawiony, ale nie ma lokalizacji - zwracam go właścicielowi.");
                    minion.setState(MinionState.ITEM);
                    minion.setLastActiveAt(0);
                    minion.setPendingDelivery(true);
                    minion.rotateToken();
                    minion.setDirty(true);
                    continue;
                }
                if (occupiedAt(minion.world(), minion.blockX(), minion.blockY(), minion.blockZ()) != null) {
                    plugin.getLogger().warning("Dwa miniony w tym samym bloku (" + minion.world() + " " + minion.blockX() + ", " + minion.blockY() + ", " + minion.blockZ() + ") - minion " + minion.id() + " wraca do właściciela.");
                    minion.setState(MinionState.ITEM);
                    minion.clearLocation();
                    minion.setLastActiveAt(0);
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
        ItemStack[] items = null;
        if (s.storage() != null && s.storage().length > 0) {
            try {
                items = ItemStack.deserializeItemsFromBytes(s.storage());
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Nie udało się odczytać magazynu miniona " + s.id()
                        + " - magazyn będzie pusty.", e);
            }
        }
        MinionStorage storage = MinionStorage.of(items, 1);
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
        m.setLinkedChest(s.linkedChest());
        m.setFuel(s.fuelType(), s.fuelMs());
        m.setLastActiveAt(s.lastActiveAt());
        storage.setCapacity(stats(m).capacity());
        return m;
    }

    /** Uruchamia zadania, usuwa osierocone encje i aktywuje miniony w załadowanych chunkach. */
    public void start() {
        for (World world : Bukkit.getWorlds()) {
            removeOrphans(world.getEntities());
            activateWorld(world);
        }
        flushUrgent();
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
            minion.invalidateStats();
            MinionType type = plugin.types().get(minion.typeId());
            if (type != null && minion.level() > type.settings().maxLevel()) {
                plugin.getLogger().warning("Minion " + minion.id() + " ma poziom " + minion.level() + ", a typ '" + type.id()
                        + "' ma teraz " + type.settings().maxLevel() + " poziomów - obniżam do maksymalnego.");
                minion.setLevel(type.settings().maxLevel());
                minion.setDirty(true);
            }
            int capacity = stats(minion).capacity();
            if (minion.storage().capacity() != capacity) {
                minion.storage().setCapacity(capacity);
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
        flushUrgent();
        tickCursor = 0;
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

    /** Liczba postawionych minionów właściciela o danej rzadkości. */
    public int placedCount(UUID owner, String rarity) {
        int count = 0;
        for (Minion m : ownedBy(owner)) {
            if (m.state() == MinionState.PLACED && rarity.equalsIgnoreCase(m.rarity())) {
                count++;
            }
        }
        return count;
    }

    private Minion occupiedAt(String world, int x, int y, int z) {
        Map<Long, Minion> worldOccupied = occupied.get(world);
        return worldOccupied == null ? null : worldOccupied.get(ChunkKeys.packBlock(x, y, z));
    }

    private Collection<Minion> ownedBy(UUID owner) {
        Set<Minion> set = byOwner.get(owner);
        return set == null ? List.of() : set;
    }

    private void addOwner(Minion minion) {
        byOwner.computeIfAbsent(minion.owner(), k -> new LinkedHashSet<>()).add(minion);
    }

    private void removeOwner(Minion minion) {
        Set<Minion> set = byOwner.get(minion.owner());
        if (set != null) {
            set.remove(minion);
            if (set.isEmpty()) {
                byOwner.remove(minion.owner());
            }
        }
    }

    /** Efektywne statystyki: poziom x rzadkość x moduły (x paliwo). Wynik jest cache'owany w minionie. */
    public EffectiveStats stats(Minion minion) {
        EffectiveStats cached = minion.cachedStats();
        if (cached == null) {
            cached = statsFor(minion, minion.level(), minion.rarity());
            minion.setCachedStats(cached);
        }
        return cached;
    }

    /** Statystyki miniona przy podanym poziomie i rzadkości (podgląd ulepszenia). */
    public EffectiveStats statsFor(Minion minion, int levelNumber, String rarityId) {
        MinionType type = plugin.types().get(minion.typeId());
        LevelSettings level = type != null ? type.settings().level(levelNumber) : null;
        RaritySettings rarity = plugin.settings().rarity(rarityId);
        ModuleService modules = plugin.modules();
        double fuel = modules.fuelMultiplier(minion);
        long baseInterval = Math.max(1000L, Math.round((level != null ? level.intervalSeconds() : 15) * 1000.0
                / rarity.speedMultiplier()));
        long interval = Math.max(1000L, Math.round(baseInterval / fuel));
        int capacity = (int) Math.max(1, Math.min(TypeSettings.MAX_CAPACITY,
                Math.round((level != null ? level.capacity() : 9) * rarity.capacityMultiplier())));
        int range = (level != null ? level.range() : 2) + modules.rangeBonus(minion);
        double drop = (level != null ? level.dropMultiplier() : 1.0) * rarity.dropMultiplier();
        int typeMax = type != null ? type.settings().maxLevel() : 1;
        int maxLevel = rarity.maxLevel() > 0 ? Math.min(typeMax, rarity.maxLevel()) : typeMax;
        return new EffectiveStats(interval, baseInterval, fuel, capacity, range, drop, rarity.bonusDropChance(),
                Math.max(1, maxLevel), rarity.moduleSlots());
    }

    public List<Minion> placedBy(UUID owner) {
        List<Minion> result = new ArrayList<>();
        for (Minion m : ownedBy(owner)) {
            if (m.state() == MinionState.PLACED) {
                result.add(m);
            }
        }
        result.sort(Comparator.comparingLong(Minion::createdAt));
        return result;
    }

    public int pendingCount(UUID owner) {
        int count = 0;
        for (Minion m : ownedBy(owner)) {
            if (m.pendingDelivery() && m.state() == MinionState.ITEM) {
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
        occupied.computeIfAbsent(minion.world(), w -> new HashMap<>())
                .put(ChunkKeys.packBlock(minion.blockX(), minion.blockY(), minion.blockZ()), minion);
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
        Map<Long, Minion> worldOccupied = occupied.get(minion.world());
        if (worldOccupied != null) {
            worldOccupied.remove(ChunkKeys.packBlock(minion.blockX(), minion.blockY(), minion.blockZ()), minion);
            if (worldOccupied.isEmpty()) {
                occupied.remove(minion.world());
            }
        }
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
        long now = System.currentTimeMillis();
        if (minion.lastActiveAt() > 0) {
            simulateOffline(minion, world, now - minion.lastActiveAt(), now);
        }
        if (minion.lastActiveAt() != 0) {
            minion.setLastActiveAt(0);
            // Natychmiastowy zapis: przy crashu przed autozapisem produkcja offline nie może się powtórzyć.
            urgentSave.add(minion);
        }
        plugin.visuals().spawn(minion, world);
        active.add(minion);
        long interval = stats(minion).intervalMs();
        // rozłożenie pracy w czasie, żeby miniony z jednego chunka nie pracowały w tym samym ticku
        minion.setNextWorkAt(now + ThreadLocalRandom.current().nextLong(Math.max(1, interval)));
    }

    private void deactivate(Minion minion) {
        plugin.visuals().despawn(minion);
        if (active.remove(minion) && minion.state() == MinionState.PLACED) {
            // Od tej chwili liczy się czas pracy offline - chyba że minion nie miał miejsca/celu pracy
            // (wtedy online też by nic nie wyprodukował, więc nie dostaje produkcji offline).
            boolean blocked = minion.status() == MinionStatus.NO_SPACE || minion.status() == MinionStatus.NO_TARGET;
            minion.setLastActiveAt(blocked ? Minion.NO_OFFLINE : System.currentTimeMillis());
            minion.setDirty(true);
        }
        minion.setStatus(MinionStatus.IDLE);
    }

    /**
     * Symulowana produkcja za czas, gdy chunk miniona był wyładowany (limit godzin z configu).
     * Uwzględnia rzadkość, poziom, paliwo i moduły. Nadmiar, który się nie zmieścił, przy aktywnej
     * auto-sprzedaży jest sprzedawany, w przeciwnym razie przepada (jak przy pełnym magazynie).
     */
    private void simulateOffline(Minion minion, World world, long elapsed, long now) {
        if (!plugin.settings().offline().enabled() || elapsed <= 0) {
            return;
        }
        MinionType type = plugin.types().get(minion.typeId());
        if (type == null || !type.isEnabled() || !type.supportsOffline()) {
            return;
        }
        elapsed = Math.min(elapsed, plugin.settings().offline().maxHours() * 3_600_000L);
        EffectiveStats stats = stats(minion);
        long boosted = stats.fuelMultiplier() > 1.0 ? Math.min(elapsed, minion.fuelMs()) : 0;
        double cycles = boosted / (stats.baseIntervalMs() / stats.fuelMultiplier())
                + (double) (elapsed - boosted) / stats.baseIntervalMs();
        if (boosted > 0) {
            minion.setFuel(minion.fuelType(), minion.fuelMs() - boosted);
        }
        long wholeCycles = (long) cycles;
        if (wholeCycles <= 0) {
            return;
        }
        List<ItemStack> produced;
        try {
            produced = plugin.modules().processBulkDrops(minion, type.simulateOffline(minion, wholeCycles));
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Błąd symulacji offline miniona " + minion.id(), e);
            return;
        }
        // Sumowanie per przedmiot i dokładanie stosami aż do zapełnienia - bez tysięcy przeszukań magazynu.
        List<ItemStack> overflow = new ArrayList<>();
        for (Drops.Total total : Drops.merge(produced)) {
            ItemStack template = total.template();
            int max = Math.max(1, template.getMaxStackSize());
            long amount = total.amount();
            while (amount > 0) {
                int part = (int) Math.min(max, amount);
                ItemStack stack = template.clone();
                stack.setAmount(part);
                int left = minion.storage().addPartial(stack);
                amount -= part - left;
                if (left > 0) {
                    break; // magazyn pełny dla tego przedmiotu
                }
            }
            if (amount > 0) {
                Drops.addSplit(overflow, template, (int) Math.min(amount, Integer.MAX_VALUE));
            }
        }
        plugin.modules().sellOverflow(minion, overflow);
        plugin.modules().afterWork(minion, world, now);
        minion.setLastWorkAt(now);
        minion.setDirty(true);
    }

    private List<BlockPos> computeArea(Minion minion, World world) {
        MinionType type = plugin.types().get(minion.typeId());
        if (type == null || !type.usesBlockArea()) {
            return List.of();
        }
        int range = stats(minion).range();
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
            flushUrgent();
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
        flushUrgent();
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
        long delta = lastTickAt > 0 ? Math.min(now - lastTickAt, 60_000L) : 0;
        lastTickAt = now;
        boolean healthCheck = now >= nextHealthCheckAt;
        if (healthCheck) {
            nextHealthCheckAt = now + HEALTH_CHECK_INTERVAL_MS;
        }
        long deadline = System.nanoTime() + plugin.settings().workBudgetMs() * 1_000_000L;

        tickBuffer.clear();
        tickBuffer.addAll(active);
        int size = tickBuffer.size();
        int start = tickCursor < size ? tickCursor : 0;
        String lastWorldName = null;
        World lastWorld = null;
        int processed = 0;
        for (; processed < size; processed++) {
            Minion minion = tickBuffer.get((start + processed) % size);
            if (!active.contains(minion)) {
                continue; // dezaktywowany w trakcie tego taktu
            }
            // cache świata - większość minionów w jednym świecie wysp
            World world;
            if (minion.world().equals(lastWorldName)) {
                world = lastWorld;
            } else {
                world = Bukkit.getWorld(minion.world());
                lastWorldName = minion.world();
                lastWorld = world;
            }
            if (world == null || !world.isChunkLoaded(minion.chunkX(), minion.chunkZ())) {
                plugin.gui().closeAll(minion.id());
                deactivate(minion);
                continue;
            }
            if (healthCheck) {
                if (!plugin.visuals().isIntact(minion)) {
                    plugin.visuals().spawn(minion, world);
                } else {
                    keepInPlace(minion, world);
                }
            }
            plugin.modules().consumeFuel(minion, delta);
            if (now < minion.nextWorkAt()) {
                continue;
            }
            if (System.nanoTime() > deadline) {
                break; // budżet czasu wyczerpany - reszta minionów w kolejnym takcie
            }
            work(minion, world, now);
        }
        tickCursor = processed < size ? (start + processed) % size : 0;
        if (processed < size) {
            // paliwo pozostałych minionów i tak musi się zużywać
            for (int i = processed + 1; i < size; i++) {
                Minion minion = tickBuffer.get((start + i) % size);
                if (active.contains(minion)) {
                    plugin.modules().consumeFuel(minion, delta);
                }
            }
        }
        tickBuffer.clear();
        flushUrgent();
    }

    private void work(Minion minion, World world, long now) {
        MinionType type = plugin.types().get(minion.typeId());
        if (type == null || !type.isEnabled()) {
            minion.setNextWorkAt(now + 5000L);
            minion.setStatus(MinionStatus.IDLE);
            plugin.visuals().updateHologram(minion);
            return;
        }
        minion.setNextWorkAt(now + stats(minion).intervalMs());
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
        try {
            if (plugin.modules().afterWork(minion, world, now)) {
                urgentSave.add(minion);
            }
        } catch (RuntimeException e) {
            if (reportedWorkErrors.add("modules")) {
                plugin.getLogger().log(Level.SEVERE, "Błąd modułów miniona " + minion.id(), e);
            }
        }
        if (minion.storage().totalItems() != before) {
            minion.setDirty(true);
            plugin.gui().refresh(minion.id());
        }
        plugin.visuals().updateHologram(minion);
    }

    /** Zapisuje w jednej transakcji miniony wymagające natychmiastowego zapisu. */
    private void flushUrgent() {
        if (urgentSave.isEmpty()) {
            return;
        }
        List<MinionSnapshot> batch = new ArrayList<>(urgentSave.size());
        for (Minion minion : urgentSave) {
            minion.setDirty(false);
            batch.add(snapshot(minion));
        }
        urgentSave.clear();
        plugin.database().saveAsync(batch, this::onSaveFailure);
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

    private boolean killForMinion(Minion minion, LivingEntity entity, boolean dropExp) {
        if (pendingKill != null || entity.isDead()) {
            return false;
        }
        pendingKill = new PendingKill(minion, entity.getUniqueId(), dropExp);
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
        for (ItemStack stack : plugin.modules().processDrops(kill.minion(), event.getDrops())) {
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
        minion.storage().setCapacity(stats(minion).capacity());
        minion.setArea(null);
        if (active.contains(minion)) {
            World world = Bukkit.getWorld(minion.world());
            if (world != null) {
                minion.setArea(computeArea(minion, world));
                plugin.visuals().spawn(minion, world);
            }
        }
    }

    /** Po zmianie rzadkości: jak po zmianie poziomu (pojemność, mnożniki, hologram). */
    public void rarityChanged(Minion minion, String previousRarity) {
        applyLevelChange(minion);
    }

    // =====================================================================================
    // Tworzenie / stawianie / zbieranie
    // =====================================================================================

    /** Tworzy nowego miniona w stanie przedmiotu i od razu zapisuje go w bazie. */
    public Minion createItemMinion(MinionType type, Player owner, int level, String rarity) {
        int lvl = Math.max(1, Math.min(level, type.settings().maxLevel()));
        Minion minion = new Minion(UUID.randomUUID(), owner.getUniqueId(), owner.getName(), type.id(),
                rarity == null ? PluginSettings.DEFAULT_RARITY : rarity, lvl, new MinionStorage(1),
                System.currentTimeMillis());
        minion.storage().setCapacity(stats(minion).capacity());
        minion.setState(MinionState.ITEM);
        minions.put(minion.id(), minion);
        addOwner(minion);
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
        if (occupiedAt(world.getName(), target.getX(), target.getY(), target.getZ()) != null) {
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
        int rarityLimit = plugin.limits().rarityLimit(player, minion.rarity());
        int rarityCount = placedCount(player.getUniqueId(), minion.rarity());
        if (rarityCount >= rarityLimit) {
            msg.send(player, "limit-rzadkosci",
                    Placeholder.component("rzadkosc", pl.skyblock.miniony.message.Text.parse(plugin.settings().rarityName(minion.rarity()))),
                    Placeholder.unparsed("liczba", String.valueOf(rarityCount)),
                    Placeholder.component("limit", limitComponent(rarityLimit)));
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
        removeOwner(minion);
        minion.setOwner(player.getUniqueId(), player.getName());
        addOwner(minion);
        minion.setIslandId(islandId);
        minion.setLocation(world.getName(), location.getX(), location.getY(), location.getZ(), yaw);
        minion.setState(MinionState.PLACED);
        minion.setPendingDelivery(false);
        minion.rotateToken();
        minion.setStatus(MinionStatus.IDLE);
        minion.setLastWorkAt(System.currentTimeMillis());
        minion.setLastActiveAt(0); // świeżo postawiony minion nie dostaje produkcji offline
        minion.setLinkedChest(null); // skrzynia była w starym miejscu
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
        minion.setLastActiveAt(0);
        minion.setLinkedChest(null);
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
            minion.setLastActiveAt(0);
            minion.setLinkedChest(null);
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
        for (Minion minion : ownedBy(player.getUniqueId())) {
            if (minion.pendingDelivery() && minion.state() == MinionState.ITEM) {
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

    /**
     * Przedmiot miniona został zniszczony (lawa, ogień, kaktus, wybuch, pustka, zniknięcie po 5 min).
     * Jeśli to aktualny przedmiot (zgodny token), minion przestaje istnieć - usuwamy go z pamięci i bazy.
     */
    public void onMinionItemDestroyed(ItemStack stack, String cause) {
        UUID id = plugin.items().readId(stack);
        UUID token = plugin.items().readToken(stack);
        Minion minion = get(id);
        if (minion == null || token == null || minion.state() != MinionState.ITEM || minion.pendingDelivery()
                || !token.equals(minion.token())) {
            return;
        }
        minions.remove(minion.id());
        removeOwner(minion);
        urgentSave.remove(minion);
        plugin.database().deleteAsync(minion.id());
        plugin.getLogger().info("Przedmiot miniona " + minion.id() + " (" + minion.typeId() + ", właściciel " + minion.ownerName()
                + ") został zniszczony (" + cause + ") - minion usunięty z bazy.");
    }

    /** Aktualizuje nick właściciela (hologram, lista) po wejściu gracza. */
    public void updateOwnerName(Player player) {
        for (Minion minion : ownedBy(player.getUniqueId())) {
            if (!player.getName().equals(minion.ownerName())) {
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
                String.join(",", m.modules()), m.pendingDelivery(), m.createdAt(), m.lastWorkAt(),
                m.linkedChest(), m.fuelType(), m.fuelMs(), m.lastActiveAt());
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
        // "znak życia" serwera - do rozliczenia pracy offline po crashu
        plugin.database().setMetaAsync("heartbeat", String.valueOf(System.currentTimeMillis()));
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
