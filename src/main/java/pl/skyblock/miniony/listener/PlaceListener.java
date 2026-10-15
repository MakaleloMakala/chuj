package pl.skyblock.miniony.listener;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Switch;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import pl.skyblock.miniony.MinionyPlugin;

import java.util.EnumSet;
import java.util.Set;

/** Stawianie minionów z przedmiotu oraz blokada stawiania przedmiotu miniona jako zwykłego bloku. */
public final class PlaceListener implements Listener {

    private final MinionyPlugin plugin;

    public PlaceListener(MinionyPlugin plugin) {
        this.plugin = plugin;
    }

    /** Tryb łączenia skrzyni (moduł "łącze ze skrzynią") - kliknięcie skrzyni nie otwiera jej, tylko ją łączy. */
    @EventHandler(priority = EventPriority.LOW)
    public void onLinkClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null || !plugin.modules().isLinking(event.getPlayer())) {
            return;
        }
        if (plugin.modules().handleLinkClick(event.getPlayer(), event.getClickedBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (!plugin.items().isMinionItem(item)) {
            return;
        }
        EquipmentSlot hand = event.getHand();
        // Przedmiot miniona nigdy nie może zostać użyty jak zwykła głowa.
        boolean deniedByOthers = event.useItemInHand() == Event.Result.DENY;
        event.setUseItemInHand(Event.Result.DENY);
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || hand == null) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null || event.getBlockFace() == null) {
            return;
        }
        Player player = event.getPlayer();
        // Kliknięcie w blok interaktywny (skrzynia, drzwi...) bez skradania - zostawiamy vanilli.
        if (!player.isSneaking() && isUsable(clicked)) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        if (deniedByOthers) {
            return; // inny plugin (np. ochrona terenu) zablokował użycie przedmiotu
        }
        if (!player.hasPermission("miniony.use")) {
            plugin.messages().send(player, "brak-uprawnien");
            return;
        }
        Block target = clicked.isReplaceable() ? clicked : clicked.getRelative(event.getBlockFace());
        plugin.minions().tryPlace(player, hand, item, target);
    }

    private static final Set<Material> USABLE = EnumSet.of(
            Material.CRAFTING_TABLE, Material.CRAFTER, Material.ENCHANTING_TABLE, Material.ANVIL, Material.CHIPPED_ANVIL,
            Material.DAMAGED_ANVIL, Material.GRINDSTONE, Material.STONECUTTER, Material.LOOM, Material.CARTOGRAPHY_TABLE,
            Material.SMITHING_TABLE, Material.BELL, Material.NOTE_BLOCK, Material.REPEATER, Material.COMPARATOR,
            Material.DAYLIGHT_DETECTOR, Material.RESPAWN_ANCHOR, Material.BEACON, Material.CAKE);

    /** Bloki, których kliknięcie ma swoją funkcję (skrzynie, drzwi, przyciski, stoły...). */
    private static boolean isUsable(Block block) {
        if (USABLE.contains(block.getType())) {
            return true;
        }
        BlockData data = block.getBlockData();
        if (data instanceof Openable || data instanceof Switch || data instanceof Bed) {
            return true;
        }
        return block.getState(false) instanceof InventoryHolder;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (plugin.items().isMinionItem(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (plugin.items().isMinionItem(event.getItem())) {
            event.setCancelled(true);
        }
    }
}
