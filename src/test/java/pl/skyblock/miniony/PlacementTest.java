package pl.skyblock.miniony;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import pl.skyblock.miniony.model.Minion;
import pl.skyblock.miniony.model.MinionState;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rozdawanie, stawianie, ochrona tokenem i limity. */
class PlacementTest extends TestSupport {

    @Test
    void giveCreatesDistinctItems() {
        PlayerMock player = player("Gracz");
        List<ItemStack> items = give(player, "gornik", 3);
        assertEquals(3, items.size(), "3 przedmioty minionów");
        assertNotEquals(plugin.items().readId(items.get(0)), plugin.items().readId(items.get(1)), "różne id");
        Minion minion = plugin.minions().get(plugin.items().readId(items.get(0)));
        assertNotNull(minion, "minion zapisany w rejestrze");
        assertEquals(MinionState.ITEM, minion.state());
    }

    @Test
    void placeSpawnsEntitiesAndConsumesItem() {
        PlayerMock player = player("Gracz");
        ItemStack item = give(player, "gornik", 1).getFirst();
        player.getInventory().clear();
        Block ground = ground(0, 64, 0);

        placeOn(player, item, ground);

        UUID id = plugin.items().readId(item);
        Minion minion = plugin.minions().get(id);
        assertEquals(MinionState.PLACED, minion.state(), "minion postawiony");
        assertEquals(65, minion.blockY(), "stoi na bloku nad klikniętym");
        assertEquals(0, countMinionItems(player), "przedmiot zabrany z ręki");
        assertNotNull(minion.body(), "ciało (ArmorStand)");
        assertFalse(minion.body().isPersistent(), "ciało nietrwałe - nie zostanie po crashu");
        assertEquals(1, minionEntities(), "dokładnie 1 encja (hologram wyłączony w testach)");
        assertEquals(1, plugin.minions().placedCount(player.getUniqueId()));
    }

    @Test
    void copiedItemCannotBePlacedTwice() {
        PlayerMock player = player("Gracz");
        ItemStack original = give(player, "gornik", 1).getFirst();
        ItemStack copy = original.clone(); // np. kopia z trybu kreatywnego
        player.getInventory().clear();

        placeOn(player, original, ground(0, 64, 0));
        placeOn(player, copy, ground(10, 64, 10));

        assertEquals(1, plugin.minions().placedCount(player.getUniqueId()), "postawiony tylko jeden");
        assertEquals(1, minionEntities(), "encje tylko jednego miniona");
        assertEquals(0, countMinionItems(player), "kopia usunięta z ręki");
        assertTrue(world.getBlockAt(10, 65, 10).getType().isAir(), "w drugim miejscu nic nie stoi");
    }

    @Test
    void oldItemAfterPickupIsInvalid() {
        PlayerMock player = player("Gracz");
        ItemStack original = give(player, "gornik", 1).getFirst();
        player.getInventory().clear();
        placeOn(player, original, ground(0, 64, 0));
        Minion minion = plugin.minions().get(plugin.items().readId(original));

        plugin.minions().pickup(player, minion);
        assertEquals(MinionState.ITEM, minion.state(), "zebrany");
        assertEquals(1, countMinionItems(player), "nowy przedmiot w ekwipunku");
        assertEquals(0, minionEntities(), "encje usunięte");

        // stary przedmiot (sprzed postawienia) ma nieaktualny token
        player.getInventory().clear();
        placeOn(player, original, ground(5, 64, 5));
        assertEquals(MinionState.ITEM, minion.state(), "stary przedmiot nie postawił miniona");
        assertEquals(0, plugin.minions().placedCount(player.getUniqueId()));
    }

    @Test
    void limitIsEnforced() {
        PlayerMock player = player("Gracz"); // bez OP - domyślny limit 3
        List<ItemStack> items = give(player, "gornik", 4);
        player.getInventory().clear();
        for (int i = 0; i < 4; i++) {
            placeOn(player, items.get(i), ground(i * 5, 64, 0));
        }
        assertEquals(3, plugin.minions().placedCount(player.getUniqueId()), "limit domyślny 3");
        assertEquals(1, countMinionItems(player), "4. minion został w ręce");
    }

    @Test
    void permissionLimitTakesHighest() {
        PlayerMock player = player("Gracz");
        player.addAttachment(plugin, "miniony.limit.2", true);
        player.addAttachment(plugin, "miniony.limit.5", true);
        plugin.limits().refresh(player);
        assertEquals(5, plugin.limits().limit(player), "największa permisja");
        player.addAttachment(plugin, "miniony.limit.999", true);
        plugin.limits().refresh(player);
        assertEquals(50, plugin.limits().limit(player), "twardy sufit limity.maksimum");
    }

    @Test
    void cannotPlaceIntoOccupiedSpot() {
        PlayerMock player = player("Gracz");
        List<ItemStack> items = give(player, "gornik", 2);
        player.getInventory().clear();
        Block ground = ground(0, 64, 0);
        placeOn(player, items.get(0), ground);
        placeOn(player, items.get(1), ground);
        assertEquals(1, plugin.minions().placedCount(player.getUniqueId()), "drugi minion nie wejdzie w ten sam blok");
    }

    @Test
    void minionItemIsNeverPlacedAsHead() {
        PlayerMock player = player("Gracz");
        ItemStack item = give(player, "gornik", 1).getFirst();
        var event = placeOn(player, item, ground(0, 64, 0));
        assertEquals(org.bukkit.event.Event.Result.DENY, event.useItemInHand(), "zwykłe postawienie głowy zablokowane");
        assertNotEquals(Material.PLAYER_HEAD, world.getBlockAt(0, 65, 0).getType(), "w świecie nie ma bloku głowy");
    }
}
