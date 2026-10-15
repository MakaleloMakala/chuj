package pl.skyblock.miniony.manager;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import pl.skyblock.miniony.MinionyPlugin;
import pl.skyblock.miniony.config.ModuleKind;
import pl.skyblock.miniony.config.ModuleSettings;
import pl.skyblock.miniony.config.ModulesSettings;
import pl.skyblock.miniony.message.Messages;
import pl.skyblock.miniony.model.EffectiveStats;
import pl.skyblock.miniony.model.Minion;
import pl.skyblock.miniony.model.MinionState;
import pl.skyblock.miniony.model.MinionStorage;
import pl.skyblock.miniony.type.MinionType;
import pl.skyblock.miniony.util.Drops;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Moduły minionów. Moduł jest aktywny, gdy: jest włączony w configu, mieści się w slotach rzadkości
 * (liczą się pierwsze N zainstalowanych) i jest dozwolony dla typu miniona.
 * Wszystkie metody - wyłącznie główny wątek.
 */
public final class ModuleService {

    private static final long LINK_TIMEOUT_MS = 30_000L;

    private final MinionyPlugin plugin;
    /** gracz -> oczekujące ustawienie skrzyni */
    private final Map<UUID, PendingLink> pendingLinks = new HashMap<>();
    private Map<Material, ItemStack> smeltMap;

    private record PendingLink(UUID minionId, long expiresAt) {
    }

    public ModuleService(MinionyPlugin plugin) {
        this.plugin = plugin;
    }

    /** Po przeładowaniu configu. */
    public void reload() {
        smeltMap = null;
    }

    private ModulesSettings settings() {
        return plugin.settings().modules();
    }

    // =====================================================================================
    // Stan modułów
    // =====================================================================================

    public boolean isActive(Minion minion, ModuleKind kind) {
        ModuleSettings settings = settings().get(kind);
        if (settings == null || !settings.enabled() || !settings.allows(minion.typeId())) {
            return false;
        }
        int slots = plugin.settings().rarity(minion.rarity()).moduleSlots();
        List<String> modules = minion.modules();
        for (int i = 0; i < modules.size() && i < slots; i++) {
            if (modules.get(i).equalsIgnoreCase(kind.id())) {
                return true;
            }
        }
        return false;
    }

    /** Mnożnik szybkości z paliwa (1, gdy brak paliwa albo modułu). */
    public double fuelMultiplier(Minion minion) {
        if (minion.fuelMs() <= 0 || minion.fuelType() == null || !isActive(minion, ModuleKind.FUEL)) {
            return 1.0;
        }
        Material material = Material.matchMaterial(minion.fuelType());
        ModulesSettings.Fuel fuel = material == null ? null : settings().fuels().get(material);
        return fuel == null ? 1.0 : fuel.speedMultiplier();
    }

    public int rangeBonus(Minion minion) {
        return isActive(minion, ModuleKind.RANGE) ? settings().rangeBonus() : 0;
    }

    // =====================================================================================
    // Drop: mnożniki, bonus, auto-przetapianie
    // =====================================================================================

    /**
     * Przetwarza drop z jednego cyklu pracy: mnożnik (poziom x rzadkość), szansa na bonusowy drop
     * (podwójny drop) i auto-przetapianie.
     */
    public List<ItemStack> processDrops(Minion minion, Collection<ItemStack> drops) {
        EffectiveStats stats = plugin.minions().stats(minion);
        List<ItemStack> result = Drops.multiply(drops, stats.dropMultiplier());
        if (stats.bonusDropChance() > 0 && ThreadLocalRandom.current().nextDouble() < stats.bonusDropChance()) {
            List<ItemStack> bonus = new ArrayList<>(result.size());
            for (ItemStack stack : result) {
                bonus.add(stack.clone());
            }
            result.addAll(bonus);
        }
        return smeltIfActive(minion, result);
    }

    /** Drop z symulacji offline: wartość oczekiwana bonusu zamiast losowania na każdy cykl. */
    public List<ItemStack> processBulkDrops(Minion minion, Collection<ItemStack> drops) {
        EffectiveStats stats = plugin.minions().stats(minion);
        return smeltIfActive(minion, Drops.multiply(drops, stats.dropMultiplier() * (1.0 + stats.bonusDropChance())));
    }

    private List<ItemStack> smeltIfActive(Minion minion, List<ItemStack> drops) {
        if (!isActive(minion, ModuleKind.AUTO_SMELT)) {
            return drops;
        }
        Map<Material, ItemStack> map = smeltMap();
        List<ItemStack> result = new ArrayList<>(drops.size());
        for (ItemStack stack : drops) {
            ItemStack smelted = settings().smeltBlacklist().contains(stack.getType()) ? null : map.get(stack.getType());
            if (smelted == null || !stack.isSimilar(new ItemStack(stack.getType()))) {
                result.add(stack);
                continue;
            }
            ItemStack template = smelted.clone();
            template.setAmount(1);
            Drops.addSplit(result, template, stack.getAmount() * Math.max(1, smelted.getAmount()));
        }
        return result;
    }

    /** Mapa przepisów pieca (materiał -> wynik), budowana leniwie z przepisów serwera. */
    private Map<Material, ItemStack> smeltMap() {
        if (smeltMap == null) {
            Map<Material, ItemStack> map = new EnumMap<>(Material.class);
            Iterator<Recipe> iterator = Bukkit.recipeIterator();
            while (iterator.hasNext()) {
                Recipe recipe = iterator.next();
                if (recipe instanceof FurnaceRecipe furnace
                        && furnace.getInputChoice() instanceof RecipeChoice.MaterialChoice choice) {
                    for (Material material : choice.getChoices()) {
                        map.putIfAbsent(material, furnace.getResult());
                    }
                }
            }
            smeltMap = map;
        }
        return smeltMap;
    }

    // =====================================================================================
    // Po cyklu pracy: kompaktor, auto-sprzedaż, łącze ze skrzynią
    // =====================================================================================

    /**
     * Przetwarzanie magazynu po pracy. Zwraca true, jeśli zmiana wymaga natychmiastowego zapisu
     * (sprzedaż / przeniesienie do skrzyni - żeby przy crashu przedmioty nie wróciły do magazynu).
     */
    public boolean afterWork(Minion minion, World world, long now) {
        boolean urgent = false;
        if (isActive(minion, ModuleKind.COMPACTOR)) {
            compact(minion.storage());
        }
        if (isActive(minion, ModuleKind.AUTO_SELL) && (now >= minion.nextSellAt() || minion.storage().freeSlots() == 0)) {
            minion.setNextSellAt(now + settings().sellIntervalSeconds() * 1000L);
            urgent |= sellStorage(minion) > 0;
        }
        if (minion.linkedChest() != null && isActive(minion, ModuleKind.CHEST_LINK) && world != null) {
            urgent |= pushToChest(minion, world);
        }
        return urgent;
    }

    private void compact(MinionStorage storage) {
        if (storage.size() > storage.capacity()) {
            return; // magazyn ma nadmiarowe sloty (zmniejszona pojemność) - zwrot reszty mógłby się nie zmieścić
        }
        for (ModulesSettings.CompactRecipe recipe : settings().compactRecipes()) {
            ItemStack input = new ItemStack(recipe.input());
            int available = storage.countSimilar(input);
            int times = available / recipe.amount();
            if (times <= 0) {
                continue;
            }
            int removed = storage.removeSimilar(input, times * recipe.amount());
            times = removed / recipe.amount();
            int rest = removed - times * recipe.amount();
            List<ItemStack> outputs = new ArrayList<>();
            Drops.addSplit(outputs, new ItemStack(recipe.output()), times);
            int notFitted = 0;
            for (ItemStack output : outputs) {
                notFitted += storage.addPartial(output);
            }
            // Wyniki, które się nie zmieściły, i reszta z dzielenia wracają jako wejście (zawsze się zmieszczą).
            int giveBack = notFitted * recipe.amount() + rest;
            if (giveBack > 0) {
                List<ItemStack> back = new ArrayList<>();
                Drops.addSplit(back, input, giveBack);
                for (ItemStack stack : back) {
                    storage.addPartial(stack);
                }
            }
        }
    }

    /** Sprzedaje "czyste" przedmioty z cennika. Zwraca zarobioną kwotę (0, gdy nic nie sprzedano). */
    public double sellStorage(Minion minion) {
        Map<Material, Double> prices = settings().sellPrices();
        if (prices.isEmpty() || !plugin.economy().isAvailable()) {
            return 0;
        }
        MinionStorage storage = minion.storage();
        double total = 0;
        Map<Integer, ItemStack> removed = new HashMap<>();
        for (int i = 0; i < storage.size(); i++) {
            ItemStack stack = storage.get(i);
            if (stack == null) {
                continue;
            }
            Double price = prices.get(stack.getType());
            if (price == null || !stack.isSimilar(new ItemStack(stack.getType()))) {
                continue;
            }
            total += price * stack.getAmount();
            removed.put(i, stack);
            storage.set(i, null);
        }
        if (total <= 0) {
            return 0;
        }
        if (!plugin.economy().depositOffline(minion.owner(), total)) {
            // Wpłata się nie powiodła - przywracamy przedmioty.
            for (Map.Entry<Integer, ItemStack> entry : removed.entrySet()) {
                storage.set(entry.getKey(), entry.getValue());
            }
            return 0;
        }
        notifySale(minion, total);
        return total;
    }

    /** Sprzedaż nadmiaru z symulacji offline (przedmioty, które nie zmieściły się w magazynie). */
    public double sellOverflow(Minion minion, Collection<ItemStack> overflow) {
        Map<Material, Double> prices = settings().sellPrices();
        if (prices.isEmpty() || !plugin.economy().isAvailable() || !isActive(minion, ModuleKind.AUTO_SELL)) {
            return 0;
        }
        double total = 0;
        for (ItemStack stack : overflow) {
            Double price = prices.get(stack.getType());
            if (price != null && stack.isSimilar(new ItemStack(stack.getType()))) {
                total += price * stack.getAmount();
            }
        }
        if (total > 0 && plugin.economy().depositOffline(minion.owner(), total)) {
            notifySale(minion, total);
            return total;
        }
        return 0;
    }

    private void notifySale(Minion minion, double total) {
        if (!settings().sellNotify()) {
            return;
        }
        Player owner = Bukkit.getPlayer(minion.owner());
        if (owner != null) {
            plugin.messages().send(owner, "sprzedano", MinionPlaceholders.of(plugin, minion),
                    Placeholder.unparsed("kwota", plugin.economy().format(total)));
        }
    }

    /** Przenosi magazyn do połączonej skrzyni (tylko gdy jej chunk jest załadowany). */
    private boolean pushToChest(Minion minion, World world) {
        int[] pos = parseLink(minion.linkedChest(), world.getName());
        if (pos == null) {
            minion.setLinkedChest(null);
            minion.setDirty(true);
            return false;
        }
        if (!world.isChunkLoaded(pos[0] >> 4, pos[2] >> 4)) {
            return false;
        }
        BlockState state = world.getBlockAt(pos[0], pos[1], pos[2]).getState(false);
        if (!(state instanceof Container container)) {
            // skrzynia zniszczona - zrywamy połączenie
            minion.setLinkedChest(null);
            minion.setDirty(true);
            return false;
        }
        Inventory inventory = container.getInventory();
        MinionStorage storage = minion.storage();
        boolean moved = false;
        for (int i = 0; i < storage.size(); i++) {
            ItemStack stack = storage.get(i);
            if (stack == null) {
                continue;
            }
            Map<Integer, ItemStack> rest = inventory.addItem(stack.clone());
            int left = 0;
            for (ItemStack r : rest.values()) {
                left += r.getAmount();
            }
            if (left == stack.getAmount()) {
                continue; // skrzynia pełna dla tego przedmiotu
            }
            moved = true;
            if (left <= 0) {
                storage.set(i, null);
            } else {
                ItemStack keep = stack.clone();
                keep.setAmount(left);
                storage.set(i, keep);
            }
        }
        return moved;
    }

    /** "świat;x;y;z" -> {x, y, z} albo null, gdy format lub świat się nie zgadza. */
    private static int[] parseLink(String link, String world) {
        if (link == null) {
            return null;
        }
        String[] parts = link.split(";");
        if (parts.length != 4 || !parts[0].equals(world)) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // =====================================================================================
    // Paliwo
    // =====================================================================================

    /** Zużycie paliwa przez aktywnego miniona. */
    public void consumeFuel(Minion minion, long deltaMs) {
        if (minion.fuelMs() <= 0 || deltaMs <= 0 || !isActive(minion, ModuleKind.FUEL)) {
            return;
        }
        long left = minion.fuelMs() - deltaMs;
        minion.setFuel(minion.fuelType(), left);
        if (left <= 0) {
            minion.setDirty(true);
        }
    }

    /** Dolewa paliwo z ekwipunku gracza (jedną sztukę albo cały stos przy shift). */
    public void addFuel(Player player, Minion minion, boolean wholeStack) {
        Messages msg = plugin.messages();
        if (!isActive(minion, ModuleKind.FUEL)) {
            msg.send(player, "modul-nieaktywny");
            return;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getStorageContents();
        int slot = -1;
        ModulesSettings.Fuel fuel = null;
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || plugin.items().isMinionItem(stack)) {
                continue;
            }
            ModulesSettings.Fuel candidate = settings().fuels().get(stack.getType());
            if (candidate != null && stack.isSimilar(new ItemStack(stack.getType()))) {
                if (minion.fuelMs() > 0 && minion.fuelType() != null && !minion.fuelType().equals(stack.getType().name())) {
                    continue; // inne paliwo niż aktualnie używane
                }
                slot = i;
                fuel = candidate;
                break;
            }
        }
        if (fuel == null) {
            msg.send(player, minion.fuelMs() > 0 ? "paliwo-inny-typ" : "brak-paliwa");
            return;
        }
        ItemStack stack = contents[slot];
        long maxMs = settings().fuelMaxHours() * 3_600_000L;
        long perItem = fuel.seconds() * 1000L;
        long room = maxMs - Math.max(0, minion.fuelMs());
        int count = (int) Math.min(wholeStack ? stack.getAmount() : 1, room / perItem);
        if (count <= 0) {
            msg.send(player, "paliwo-pelne");
            return;
        }
        if (count >= stack.getAmount()) {
            inventory.setItem(slot, null);
        } else {
            ItemStack rest = stack.clone();
            rest.setAmount(stack.getAmount() - count);
            inventory.setItem(slot, rest);
        }
        minion.setFuel(stack.getType().name(), Math.max(0, minion.fuelMs()) + perItem * count);
        player.saveData();
        plugin.minions().storageChangedByPlayer(minion);
        msg.send(player, "paliwo-dodane", Placeholder.unparsed("ilosc", String.valueOf(count)),
                Placeholder.unparsed("czas", formatDuration(minion.fuelMs())));
    }

    public static String formatDuration(long ms) {
        long seconds = Math.max(0, ms / 1000);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
                : String.format(Locale.ROOT, "%d:%02d", minutes, secs);
    }

    // =====================================================================================
    // Instalacja / deinstalacja
    // =====================================================================================

    public void install(Player player, Minion minion, ModuleKind kind) {
        Messages msg = plugin.messages();
        UpgradeService upgrades = plugin.upgrades();
        if (!upgrades.checkCooldown(player)) {
            return;
        }
        if (minion.state() != MinionState.PLACED || plugin.minions().get(minion.id()) != minion) {
            msg.send(player, "minion-niedostepny");
            return;
        }
        if (!upgrades.canUpgrade(player, minion)) {
            msg.send(player, "tylko-wlasciciel-ulepsza");
            return;
        }
        ModuleSettings settings = settings().get(kind);
        if (settings == null || !settings.enabled()) {
            msg.send(player, "modul-wylaczony");
            return;
        }
        if (!settings.allows(minion.typeId())) {
            msg.send(player, "modul-niedozwolony");
            return;
        }
        if (minion.hasModule(kind.id())) {
            msg.send(player, "modul-juz-zainstalowany");
            return;
        }
        int slots = plugin.settings().rarity(minion.rarity()).moduleSlots();
        if (minion.modules().size() >= slots) {
            msg.send(player, "brak-slotu-modulu");
            return;
        }
        if (!upgrades.pay(player, settings.cost(), "moduł '" + kind.id() + "'")) {
            return;
        }
        minion.modules().add(kind.id());
        minion.invalidateStats();
        plugin.minions().applyLevelChange(minion);
        player.saveData();
        plugin.minions().storageChangedByPlayer(minion);
        msg.send(player, "modul-zainstalowany", Placeholder.component("modul",
                pl.skyblock.miniony.message.Text.parse(settings.name())));
        plugin.getLogger().info("Gracz " + player.getName() + " zainstalował moduł '" + kind.id() + "' w minionie " + minion.id() + ".");
    }

    public void uninstall(Player player, Minion minion, int index) {
        Messages msg = plugin.messages();
        if (minion.state() != MinionState.PLACED || plugin.minions().get(minion.id()) != minion) {
            msg.send(player, "minion-niedostepny");
            return;
        }
        if (!plugin.upgrades().canUpgrade(player, minion)) {
            msg.send(player, "tylko-wlasciciel-ulepsza");
            return;
        }
        if (index < 0 || index >= minion.modules().size()) {
            return;
        }
        String id = minion.modules().remove(index);
        minion.invalidateStats();
        ModuleKind kind = ModuleKind.byId(id);
        if (kind == ModuleKind.FUEL) {
            minion.setFuel(null, 0);
        } else if (kind == ModuleKind.CHEST_LINK) {
            minion.setLinkedChest(null);
        }
        plugin.minions().applyLevelChange(minion);
        plugin.minions().storageChangedByPlayer(minion);
        ModuleSettings settings = kind == null ? null : settings().get(kind);
        msg.send(player, "modul-odinstalowany", Placeholder.component("modul",
                pl.skyblock.miniony.message.Text.parse(settings == null ? id : settings.name())));
    }

    // =====================================================================================
    // Łącze ze skrzynią
    // =====================================================================================

    public void startLink(Player player, Minion minion) {
        if (!isActive(minion, ModuleKind.CHEST_LINK)) {
            plugin.messages().send(player, "modul-nieaktywny");
            return;
        }
        if (!plugin.upgrades().canUpgrade(player, minion)) {
            plugin.messages().send(player, "tylko-wlasciciel-ulepsza");
            return;
        }
        pendingLinks.put(player.getUniqueId(), new PendingLink(minion.id(), System.currentTimeMillis() + LINK_TIMEOUT_MS));
        player.closeInventory();
        plugin.messages().send(player, "lacze-tryb", Placeholder.unparsed("odleglosc", String.valueOf(settings().chestMaxDistance())));
    }

    public boolean isLinking(Player player) {
        return pendingLinks.containsKey(player.getUniqueId());
    }

    public void cancelLink(UUID player) {
        pendingLinks.remove(player);
    }

    /** Kliknięcie bloku w trybie łączenia. Zwraca true, jeśli kliknięcie zostało obsłużone (zdarzenie do anulowania). */
    public boolean handleLinkClick(Player player, Block block) {
        PendingLink pending = pendingLinks.remove(player.getUniqueId());
        if (pending == null) {
            return false;
        }
        Messages msg = plugin.messages();
        if (System.currentTimeMillis() > pending.expiresAt()) {
            msg.send(player, "lacze-anulowane");
            return false;
        }
        Minion minion = plugin.minions().get(pending.minionId());
        if (minion == null || minion.state() != MinionState.PLACED || !isActive(minion, ModuleKind.CHEST_LINK)) {
            msg.send(player, "minion-niedostepny");
            return true;
        }
        if (!plugin.upgrades().canUpgrade(player, minion)) {
            msg.send(player, "tylko-wlasciciel-ulepsza");
            return true;
        }
        if (!(block.getState(false) instanceof Container)) {
            msg.send(player, "lacze-nie-skrzynia");
            return true;
        }
        if (!block.getWorld().getName().equals(minion.world())) {
            msg.send(player, "lacze-za-daleko", Placeholder.unparsed("odleglosc", String.valueOf(settings().chestMaxDistance())));
            return true;
        }
        double dx = block.getX() + 0.5 - minion.x();
        double dy = block.getY() + 0.5 - minion.y();
        double dz = block.getZ() + 0.5 - minion.z();
        int max = settings().chestMaxDistance();
        if (dx * dx + dy * dy + dz * dz > (double) max * max) {
            msg.send(player, "lacze-za-daleko", Placeholder.unparsed("odleglosc", String.valueOf(max)));
            return true;
        }
        if (plugin.islands().isEnabled() && minion.islandId() != null
                && !plugin.islands().isInsideIsland(minion.islandId(), block.getLocation())) {
            msg.send(player, "lacze-inna-wyspa");
            return true;
        }
        minion.setLinkedChest(block.getWorld().getName() + ";" + block.getX() + ";" + block.getY() + ";" + block.getZ());
        plugin.minions().storageChangedByPlayer(minion);
        msg.send(player, "lacze-ustawione");
        return true;
    }

    /** Czy moduł jest dostępny dla typu (do GUI). */
    public boolean allowed(MinionType type, ModuleKind kind) {
        ModuleSettings settings = settings().get(kind);
        return settings != null && settings.enabled() && type != null && settings.allows(type.id());
    }
}
