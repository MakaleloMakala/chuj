package pl.skyblock.miniony.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
import pl.skyblock.miniony.config.ModuleGuiSettings;
import pl.skyblock.miniony.config.ModuleKind;
import pl.skyblock.miniony.config.ModuleSettings;
import pl.skyblock.miniony.config.RaritySettings;
import pl.skyblock.miniony.config.UpgradeCost;
import pl.skyblock.miniony.manager.MinionPlaceholders;
import pl.skyblock.miniony.manager.ModuleService;
import pl.skyblock.miniony.message.Text;
import pl.skyblock.miniony.model.Minion;
import pl.skyblock.miniony.model.MinionState;
import pl.skyblock.miniony.type.MinionType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * GUI modułów miniona. Jak w głównym GUI: każde kliknięcie i przeciągnięcie jest anulowane,
 * a akcje wykonywane są tick później po ponownej weryfikacji stanu miniona i uprawnień.
 */
public final class ModuleGui implements Listener {

    private final MinionyPlugin plugin;
    private final Map<UUID, Set<Holder>> viewers = new HashMap<>();

    /** Holder GUI modułów. */
    public static final class Holder implements InventoryHolder {
        private final UUID minionId;
        private final UUID viewer;
        private Inventory inventory;
        /** slot -> moduł w rzędzie modułów do kupienia */
        private final Map<Integer, ModuleKind> available = new HashMap<>();

        private Holder(UUID minionId, UUID viewer) {
            this.minionId = minionId;
            this.viewer = viewer;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public ModuleGui(MinionyPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, Minion minion) {
        if (!plugin.minions().canAccess(player, minion)) {
            plugin.messages().send(player, "brak-dostepu");
            return;
        }
        Holder holder = new Holder(minion.id(), player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, ModuleGuiSettings.SIZE,
                Text.parse(plugin.settings().moduleGui().title(), MinionPlaceholders.of(plugin, minion)));
        holder.inventory = inventory;
        render(minion, holder);
        player.openInventory(inventory);
        if (Guis.openHolder(player) == holder) {
            viewers.computeIfAbsent(minion.id(), k -> new HashSet<>()).add(holder);
        }
    }

    private void render(Minion minion, Holder holder) {
        Inventory inv = holder.inventory;
        ModuleGuiSettings settings = plugin.settings().moduleGui();
        GuiSettings gui = plugin.settings().gui();
        ModuleService modules = plugin.modules();
        TagResolver base = MinionPlaceholders.of(plugin, minion);
        ItemStack filler = item(gui.fillerMaterial(), gui.fillerName(), List.of(), TagResolver.empty(), null);
        for (int slot = 0; slot < ModuleGuiSettings.SIZE; slot++) {
            inv.setItem(slot, filler);
        }

        // Rząd 1: sloty modułów miniona
        RaritySettings rarity = plugin.settings().rarity(minion.rarity());
        List<String> installed = minion.modules();
        for (int i = 0; i < RaritySettings.MAX_MODULE_SLOTS; i++) {
            if (i < installed.size()) {
                ModuleKind kind = ModuleKind.byId(installed.get(i));
                ModuleSettings ms = kind == null ? null : plugin.settings().modules().get(kind);
                boolean active = kind != null && modules.isActive(minion, kind);
                List<String> lore = new ArrayList<>(ms == null ? List.of() : ms.description());
                lore.addAll(active ? settings.installedLore() : withUninstall(settings.inactiveLore(), settings.installedLore()));
                inv.setItem(i, item(ms == null ? Material.BARRIER : ms.icon(), ms == null ? "<red>" + installed.get(i) : ms.name(),
                        lore, base, null));
            } else if (i < rarity.moduleSlots()) {
                inv.setItem(i, button(settings.freeSlot(), base));
            } else {
                inv.setItem(i, button(settings.lockedSlot(), base));
            }
        }

        // Rząd 3: moduły do zainstalowania
        holder.available.clear();
        MinionType type = plugin.types().get(minion.typeId());
        int slot = ModuleGuiSettings.AVAILABLE_ROW_START;
        for (ModuleKind kind : ModuleKind.values()) {
            ModuleSettings ms = plugin.settings().modules().get(kind);
            if (ms == null || !ms.enabled() || slot >= ModuleGuiSettings.BUTTON_ROW_START) {
                continue;
            }
            List<String> lore = new ArrayList<>(ms.description());
            UpgradeCost cost = null;
            if (minion.hasModule(kind.id())) {
                lore.addAll(settings.alreadyInstalledLore());
            } else if (!modules.allowed(type, kind)) {
                lore.addAll(settings.notAllowedLore());
            } else {
                lore.addAll(settings.availableLore());
                cost = ms.cost();
            }
            inv.setItem(slot, item(ms.icon(), ms.name(), lore, base, cost));
            holder.available.put(slot, kind);
            slot++;
        }

        // Rząd 4: przyciski
        inv.setItem(settings.back().slot(), button(settings.back(), base));
        inv.setItem(settings.close().slot(), button(settings.close(), base));
        if (modules.isActive(minion, ModuleKind.FUEL)) {
            inv.setItem(settings.fuel().slot(), button(settings.fuel(), base));
        }
        if (modules.isActive(minion, ModuleKind.CHEST_LINK)) {
            String link = minion.linkedChest();
            String shown = link == null ? "-" : link.substring(link.indexOf(';') + 1).replace(';', ' ');
            inv.setItem(settings.chestLink().slot(), button(settings.chestLink(),
                    TagResolver.resolver(base, Placeholder.unparsed("skrzynia", shown))));
        }
    }

    /** Lore nieaktywnego modułu + linie o odinstalowaniu (ostatnia linia lore zainstalowanego). */
    private static List<String> withUninstall(List<String> inactive, List<String> installed) {
        List<String> result = new ArrayList<>(inactive);
        if (!installed.isEmpty()) {
            result.add(installed.getLast());
        }
        return result;
    }

    private ItemStack button(ButtonSettings b, TagResolver resolver) {
        return item(b.material(), b.name(), b.lore(), resolver, null);
    }

    private ItemStack item(Material material, String name, List<String> lore, TagResolver resolver, UpgradeCost cost) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.item(name, resolver));
            List<Component> lines = new ArrayList<>();
            for (String line : lore) {
                if (line.trim().equals("<koszt>")) {
                    if (cost != null) {
                        lines.addAll(plugin.upgrades().costLines(cost));
                    }
                } else {
                    lines.add(Text.item(line, resolver));
                }
            }
            meta.lore(lines);
            item.setItemMeta(meta);
        }
        return item;
    }

    public void refresh(UUID minionId) {
        Set<Holder> set = viewers.get(minionId);
        if (set == null || set.isEmpty()) {
            return;
        }
        Minion minion = plugin.minions().get(minionId);
        if (minion == null || minion.state() != MinionState.PLACED) {
            closeAll(minionId);
            return;
        }
        for (Holder holder : new ArrayList<>(set)) {
            render(minion, holder);
        }
    }

    public void closeAll(UUID minionId) {
        Set<Holder> set = viewers.remove(minionId);
        if (set == null) {
            return;
        }
        for (Holder holder : set) {
            Player player = Bukkit.getPlayer(holder.viewer);
            if (player != null && Guis.openHolder(player) == holder) {
                player.closeInventory();
            }
        }
    }

    public void closeAll() {
        for (UUID id : new ArrayList<>(viewers.keySet())) {
            closeAll(id);
        }
        viewers.clear();
    }

    private static Holder holderOf(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        InventoryHolder holder = Guis.holder(inventory);
        return holder instanceof Holder h ? h : null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        Holder holder = holderOf(event.getView().getTopInventory());
        if (holder == null) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getRawSlot();
        boolean shift = event.isShiftClick();
        if (slot < 0 || slot >= ModuleGuiSettings.SIZE) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> handleClick(player, holder, slot, shift));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (holderOf(event.getView().getTopInventory()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Holder holder = holderOf(event.getInventory());
        if (holder == null) {
            return;
        }
        Set<Holder> set = viewers.get(holder.minionId);
        if (set != null) {
            set.remove(holder);
            if (set.isEmpty()) {
                viewers.remove(holder.minionId);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        viewers.values().forEach(set -> set.removeIf(h -> h.viewer.equals(uuid)));
        viewers.values().removeIf(Set::isEmpty);
    }

    private void handleClick(Player player, Holder holder, int slot, boolean shift) {
        if (!player.isOnline() || Guis.openHolder(player) != holder) {
            return;
        }
        Minion minion = plugin.minions().get(holder.minionId);
        if (minion == null || minion.state() != MinionState.PLACED) {
            player.closeInventory();
            plugin.messages().send(player, "minion-niedostepny");
            return;
        }
        if (!plugin.minions().canAccess(player, minion)) {
            player.closeInventory();
            plugin.messages().send(player, "brak-dostepu");
            return;
        }
        ModuleGuiSettings settings = plugin.settings().moduleGui();
        if (slot < RaritySettings.MAX_MODULE_SLOTS) {
            if (shift && slot < minion.modules().size()) {
                plugin.modules().uninstall(player, minion, slot);
            }
        } else if (holder.available.containsKey(slot)) {
            plugin.modules().install(player, minion, holder.available.get(slot));
        } else if (slot == settings.back().slot()) {
            plugin.gui().open(player, minion, 0);
        } else if (slot == settings.close().slot()) {
            player.closeInventory();
        } else if (slot == settings.fuel().slot() && plugin.modules().isActive(minion, ModuleKind.FUEL)) {
            plugin.modules().addFuel(player, minion, shift);
        } else if (slot == settings.chestLink().slot() && plugin.modules().isActive(minion, ModuleKind.CHEST_LINK)) {
            plugin.modules().startLink(player, minion);
        }
    }
}
