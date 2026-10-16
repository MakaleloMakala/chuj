package pl.skyblock.miniony;

import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import pl.skyblock.miniony.gui.MinionGuiHolder;
import pl.skyblock.miniony.model.Minion;
import pl.skyblock.miniony.model.MinionState;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GUI miniona: odbieranie przedmiotów i próby duplikacji. */
class GuiTest extends TestSupport {

    private PlayerMock owner;
    private Minion minion;

    @BeforeEach
    void placeMinion() {
        owner = player("Wlasciciel");
        ItemStack item = give(owner, "gornik", 1).getFirst();
        owner.getInventory().clear();
        placeOn(owner, item, ground(0, 64, 0));
        minion = plugin.minions().get(plugin.items().readId(item));
        minion.storage().add(List.of(new ItemStack(Material.COBBLESTONE, 64), new ItemStack(Material.COBBLESTONE, 10)));
    }

    private static boolean hasGuiOpen(PlayerMock player) {
        InventoryView view = player.getOpenInventory();
        return view != null && view.getTopInventory() != null
                && view.getTopInventory().getHolder(false) instanceof MinionGuiHolder;
    }

    private void openGui(PlayerMock player) {
        server.getPluginManager().callEvent(new PlayerInteractAtEntityEvent(player, minion.body(), new Vector(), EquipmentSlot.HAND));
    }

    private int storedCobble() {
        int total = 0;
        for (int i = 0; i < minion.storage().size(); i++) {
            ItemStack stack = minion.storage().get(i);
            if (stack != null && stack.getType() == Material.COBBLESTONE) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    @Test
    void ownerOpensGuiAndTakesStack() {
        openGui(owner);
        assertTrue(hasGuiOpen(owner), "GUI otwarte");

        InventoryClickEvent click = owner.simulateInventoryClick(owner.getOpenInventory(), ClickType.LEFT, 0);
        assertTrue(click.isCancelled(), "kliknięcie zawsze anulowane");
        server.getScheduler().performOneTick();

        assertEquals(64, count(owner, Material.COBBLESTONE), "gracz dostał stos");
        assertEquals(10, storedCobble(), "w magazynie została reszta");
    }

    @Test
    void collectAllButton() {
        openGui(owner);
        owner.simulateInventoryClick(owner.getOpenInventory(), ClickType.LEFT, plugin.settings().gui().collectAll().slot());
        server.getScheduler().performOneTick();
        assertEquals(74, count(owner, Material.COBBLESTONE));
        assertEquals(0, storedCobble());
    }

    @Test
    void dupeAttemptsAreCancelledAndDoNotCreateItems() {
        openGui(owner);
        InventoryView view = owner.getOpenInventory();
        int before = storedCobble() + count(owner, Material.COBBLESTONE);

        // shift-click, double click, hotbar swap, drop, środkowy - w górnym i dolnym inventory
        for (ClickType type : new ClickType[]{ClickType.SHIFT_LEFT, ClickType.DOUBLE_CLICK, ClickType.NUMBER_KEY,
                ClickType.DROP, ClickType.CONTROL_DROP, ClickType.MIDDLE, ClickType.SWAP_OFFHAND}) {
            assertTrue(owner.simulateInventoryClick(view, type, 0).isCancelled(), type + " w GUI anulowane");
            assertTrue(owner.simulateInventoryClick(view, type, 60).isCancelled(), type + " w ekwipunku anulowane");
        }
        InventoryDragEvent drag = new InventoryDragEvent(view, null, new ItemStack(Material.COBBLESTONE),
                false, Map.of(0, new ItemStack(Material.COBBLESTONE), 1, new ItemStack(Material.COBBLESTONE)));
        server.getPluginManager().callEvent(drag);
        assertTrue(drag.isCancelled(), "przeciąganie anulowane");

        server.getScheduler().performTicks(5);
        int after = storedCobble() + count(owner, Material.COBBLESTONE);
        assertTrue(after <= before, "liczba przedmiotów nie wzrosła (" + before + " -> " + after + ")");
    }

    @Test
    void doubleClickSpamDoesNotDuplicate() {
        openGui(owner);
        InventoryView view = owner.getOpenInventory();
        for (int i = 0; i < 20; i++) {
            owner.simulateInventoryClick(view, ClickType.LEFT, 0); // spam w tym samym ticku
        }
        server.getScheduler().performOneTick();
        assertEquals(74, count(owner, Material.COBBLESTONE) + storedCobble(), "łącznie nadal 74 sztuki");
    }

    @Test
    void twoPlayersTakingSameSlot() {
        PlayerMock admin = player("Admin");
        admin.addAttachment(plugin, "miniony.admin.access", true);
        openGui(owner);
        openGui(admin);
        owner.simulateInventoryClick(owner.getOpenInventory(), ClickType.LEFT, 0);
        admin.simulateInventoryClick(admin.getOpenInventory(), ClickType.LEFT, 0);
        server.getScheduler().performOneTick();
        int total = count(owner, Material.COBBLESTONE) + count(admin, Material.COBBLESTONE) + storedCobble();
        assertEquals(74, total, "żadnej kopii przy jednoczesnym kliknięciu");
    }

    @Test
    void strangerCannotOpenOrPickup() {
        PlayerMock stranger = player("Obcy");
        openGui(stranger);
        assertFalse(hasGuiOpen(stranger), "obcy nie otworzy GUI");
        plugin.minions().pickup(stranger, minion);
        assertEquals(MinionState.PLACED, minion.state(), "obcy nie zbierze miniona");
    }

    @Test
    void pickupWhileAnotherPlayerHasGuiOpen() {
        PlayerMock admin = player("Admin");
        admin.addAttachment(plugin, "miniony.admin.access", true);
        openGui(admin);
        // admin klika w magazyn, a w tym samym ticku (przed obsługą kliknięcia) właściciel zbiera miniona
        admin.simulateInventoryClick(admin.getOpenInventory(), ClickType.LEFT, 0);
        plugin.minions().pickup(owner, minion);
        server.getScheduler().performOneTick();
        assertFalse(hasGuiOpen(admin), "GUI admina zamknięte");
        assertEquals(0, count(admin, Material.COBBLESTONE), "admin nic nie dostał");
        assertEquals(74, count(owner, Material.COBBLESTONE) + storedCobble(), "zawartość magazynu nie zdublowana");
    }
}
