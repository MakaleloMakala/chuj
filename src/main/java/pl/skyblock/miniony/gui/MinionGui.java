package pl.skyblock.miniony.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.skyblock.miniony.MinionyPlugin;
import pl.skyblock.miniony.config.ButtonSettings;
import pl.skyblock.miniony.config.GuiSettings;
import pl.skyblock.miniony.config.LevelSettings;
import pl.skyblock.miniony.manager.MinionPlaceholders;
import pl.skyblock.miniony.message.Text;
import pl.skyblock.miniony.model.Minion;
import pl.skyblock.miniony.model.MinionState;
import pl.skyblock.miniony.model.MinionStorage;
import pl.skyblock.miniony.type.MinionType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * GUI miniona: podgląd magazynu i odbieranie przedmiotów.
 * <p>
 * Ochrona przed duplikacją: KAŻDE kliknięcie i przeciągnięcie przy otwartym GUI jest anulowane,
 * a przedmioty w górnym inventory to tylko kopie do wyświetlenia. Przeniesienie przedmiotu odbywa się
 * programowo (tick później) na podstawie magazynu miniona, który jest jedynym źródłem prawdy.
 */
public final class MinionGui implements Listener {

    private final MinionyPlugin plugin;
    /** minion -> otwarte widoki */
    private final Map<UUID, Set<MinionGuiHolder>> viewers = new HashMap<>();

    public MinionGui(MinionyPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, Minion minion, int page) {
        MinionGuiHolder holder = new MinionGuiHolder(minion.id(), player.getUniqueId(), Math.max(0, page));
        Inventory inventory = Bukkit.createInventory(holder, GuiSettings.SIZE, title(minion, holder));
        holder.setInventory(inventory);
        render(minion, holder);
        player.openInventory(inventory);
        // Rejestrujemy widok tylko, jeśli inventory faktycznie się otworzyło (inny plugin mógł to anulować).
        if (player.getOpenInventory().getTopInventory().getHolder(false) == holder) {
            viewers.computeIfAbsent(minion.id(), k -> new HashSet<>()).add(holder);
        }
    }

    private static int pages(Minion minion) {
        int slots = Math.max(minion.storage().size(), minion.storage().capacity());
        return Math.max(1, (slots + GuiSettings.STORAGE_SLOTS - 1) / GuiSettings.STORAGE_SLOTS);
    }

    private Component title(Minion minion, MinionGuiHolder holder) {
        return Text.parse(plugin.settings().gui().title(), MinionPlaceholders.of(plugin, minion),
                Placeholder.unparsed("strona", String.valueOf(holder.page() + 1)),
                Placeholder.unparsed("strony", String.valueOf(pages(minion))));
    }

    private void render(Minion minion, MinionGuiHolder holder) {
        Inventory inv = holder.getInventory();
        GuiSettings gui = plugin.settings().gui();
        int pages = pages(minion);
        if (holder.page() >= pages) {
            holder.setPage(pages - 1);
        }
        MinionStorage storage = minion.storage();
        ItemStack filler = simpleItem(gui.fillerMaterial(), gui.fillerName(), List.of(), TagResolver.empty());
        int offset = holder.page() * GuiSettings.STORAGE_SLOTS;
        for (int slot = 0; slot < GuiSettings.STORAGE_SLOTS; slot++) {
            int index = offset + slot;
            ItemStack stored = storage.get(index);
            if (stored != null) {
                inv.setItem(slot, displayCopy(stored, gui.itemLore()));
            } else if (index < storage.capacity()) {
                inv.setItem(slot, null);
            } else {
                inv.setItem(slot, filler);
            }
        }
        for (int slot = GuiSettings.STORAGE_SLOTS; slot < GuiSettings.SIZE; slot++) {
            inv.setItem(slot, filler);
        }
        TagResolver resolver = MinionPlaceholders.of(plugin, minion);
        if (holder.page() > 0) {
            button(inv, gui.previousPage(), resolver);
        }
        if (holder.page() < pages - 1) {
            button(inv, gui.nextPage(), resolver);
        }
        button(inv, gui.collectAll(), resolver);
        upgradeButton(inv, minion, resolver);
        button(inv, gui.info(), resolver);
        button(inv, gui.pickup(), resolver);
        button(inv, gui.close(), resolver);
    }

    private void upgradeButton(Inventory inv, Minion minion, TagResolver resolver) {
        GuiSettings gui = plugin.settings().gui();
        MinionType type = plugin.types().get(minion.typeId());
        if (type == null || minion.level() >= type.settings().maxLevel()) {
            button(inv, gui.upgradeMax(), resolver);
            return;
        }
        LevelSettings next = type.settings().level(minion.level() + 1);
        TagResolver full = TagResolver.resolver(resolver,
                Placeholder.unparsed("nastepny-poziom", String.valueOf(next.level())),
                Placeholder.unparsed("nowy-interwal", String.valueOf(next.intervalSeconds())),
                Placeholder.unparsed("nowa-pojemnosc", String.valueOf(next.capacity())),
                Placeholder.unparsed("nowy-zasieg", String.valueOf(next.range())),
                Placeholder.unparsed("nowy-mnoznik", MinionPlaceholders.formatMultiplier(next.dropMultiplier())));
        ButtonSettings b = gui.upgrade();
        ItemStack item = new ItemStack(b.material());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.item(b.name(), full));
            List<Component> lore = new ArrayList<>();
            for (String line : b.lore()) {
                if (line.trim().equals("<koszt>")) {
                    lore.addAll(plugin.upgrades().costLines(next.cost()));
                } else {
                    lore.add(Text.item(line, full));
                }
            }
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        inv.setItem(b.slot(), item);
    }

    private static void button(Inventory inv, ButtonSettings b, TagResolver resolver) {
        inv.setItem(b.slot(), simpleItem(b.material(), b.name(), b.lore(), resolver));
    }

    private static ItemStack simpleItem(org.bukkit.Material material, String name, List<String> lore, TagResolver resolver) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.item(name, resolver));
            meta.lore(Text.itemLines(lore, resolver));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack displayCopy(ItemStack stored, List<String> extraLore) {
        ItemStack copy = stored.clone();
        if (extraLore.isEmpty()) {
            return copy;
        }
        ItemMeta meta = copy.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.addAll(Text.itemLines(extraLore));
            meta.lore(lore);
            copy.setItemMeta(meta);
        }
        return copy;
    }

    /** Odświeża wszystkie otwarte widoki danego miniona. */
    public void refresh(UUID minionId) {
        Set<MinionGuiHolder> set = viewers.get(minionId);
        if (set == null || set.isEmpty()) {
            return;
        }
        Minion minion = plugin.minions().get(minionId);
        if (minion == null || minion.state() != MinionState.PLACED) {
            closeAll(minionId);
            return;
        }
        for (MinionGuiHolder holder : new ArrayList<>(set)) {
            render(minion, holder);
        }
    }

    /** Zamyka wszystkie widoki danego miniona. */
    public void closeAll(UUID minionId) {
        Set<MinionGuiHolder> set = viewers.remove(minionId);
        if (set == null) {
            return;
        }
        for (MinionGuiHolder holder : set) {
            Player player = Bukkit.getPlayer(holder.viewer());
            if (player != null && player.getOpenInventory().getTopInventory().getHolder(false) == holder) {
                player.closeInventory();
            }
        }
    }

    /** Zamyka wszystkie GUI minionów (wyłączenie / przeładowanie pluginu). */
    public void closeAll() {
        for (UUID id : new ArrayList<>(viewers.keySet())) {
            closeAll(id);
        }
        viewers.clear();
    }

    private static MinionGuiHolder holderOf(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        InventoryHolder holder = inventory.getHolder(false);
        return holder instanceof MinionGuiHolder minionHolder ? minionHolder : null;
    }

    // =====================================================================================
    // Zdarzenia
    // =====================================================================================

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        MinionGuiHolder holder = holderOf(event.getView().getTopInventory());
        if (holder == null) {
            return;
        }
        // Blokada WSZYSTKICH akcji: shift-click, double click, hotbar swap, drop, offhand itd.
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= GuiSettings.SIZE) {
            return;
        }
        // Akcje wykonujemy tick później, poza obsługą zdarzenia kliknięcia.
        Bukkit.getScheduler().runTask(plugin, () -> handleClick(player, holder, slot));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (holderOf(event.getView().getTopInventory()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        MinionGuiHolder holder = holderOf(event.getInventory());
        if (holder == null) {
            return;
        }
        Set<MinionGuiHolder> set = viewers.get(holder.minionId());
        if (set != null) {
            set.remove(holder);
            if (set.isEmpty()) {
                viewers.remove(holder.minionId());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        viewers.values().forEach(set -> set.removeIf(h -> h.viewer().equals(uuid)));
        viewers.values().removeIf(Set::isEmpty);
    }

    private void handleClick(Player player, MinionGuiHolder holder, int slot) {
        if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder(false) != holder) {
            return;
        }
        Minion minion = plugin.minions().get(holder.minionId());
        if (minion == null || minion.state() != MinionState.PLACED) {
            player.closeInventory();
            plugin.messages().send(player, "minion-niedostepny");
            return;
        }
        // Ponowna weryfikacja dostępu (np. gracz został wyrzucony z wyspy przy otwartym GUI).
        if (!plugin.minions().canAccess(player, minion)) {
            player.closeInventory();
            plugin.messages().send(player, "brak-dostepu");
            return;
        }
        GuiSettings gui = plugin.settings().gui();
        if (slot < GuiSettings.STORAGE_SLOTS) {
            takeSlot(player, minion, holder.page() * GuiSettings.STORAGE_SLOTS + slot);
        } else if (slot == gui.collectAll().slot()) {
            collectAll(player, minion);
        } else if (slot == gui.upgrade().slot()) {
            plugin.upgrades().upgrade(player, minion);
        } else if (slot == gui.pickup().slot()) {
            player.closeInventory();
            plugin.minions().pickup(player, minion);
        } else if (slot == gui.close().slot()) {
            player.closeInventory();
        } else if (slot == gui.previousPage().slot() && holder.page() > 0) {
            changePage(player, minion, holder, holder.page() - 1);
        } else if (slot == gui.nextPage().slot() && holder.page() < pages(minion) - 1) {
            changePage(player, minion, holder, holder.page() + 1);
        }
    }

    private void changePage(Player player, Minion minion, MinionGuiHolder old, int page) {
        Set<MinionGuiHolder> set = viewers.get(minion.id());
        if (set != null) {
            set.remove(old);
        }
        open(player, minion, page);
    }

    private void takeSlot(Player player, Minion minion, int index) {
        MinionStorage storage = minion.storage();
        ItemStack stored = storage.get(index);
        if (stored == null) {
            return;
        }
        int remaining = plugin.minions().giveToPlayer(player, stored);
        int taken = stored.getAmount() - remaining;
        if (taken <= 0) {
            plugin.messages().send(player, "brak-miejsca-ekwipunek");
            return;
        }
        if (remaining <= 0) {
            storage.set(index, null);
        } else {
            ItemStack rest = stored.clone();
            rest.setAmount(remaining);
            storage.set(index, rest);
        }
        plugin.minions().storageChangedByPlayer(minion);
    }

    private void collectAll(Player player, Minion minion) {
        if (minion.storage().isEmpty()) {
            plugin.messages().send(player, "magazyn-pusty");
            return;
        }
        int before = minion.storage().totalItems();
        int left = plugin.minions().transferStorage(player, minion);
        int taken = before - left;
        if (taken <= 0) {
            plugin.messages().send(player, "brak-miejsca-ekwipunek");
            return;
        }
        plugin.minions().storageChangedByPlayer(minion);
        plugin.messages().send(player, "odebrano-przedmioty", Placeholder.unparsed("ilosc", String.valueOf(taken)));
        if (left > 0) {
            plugin.messages().send(player, "brak-miejsca-ekwipunek");
        }
    }
}
