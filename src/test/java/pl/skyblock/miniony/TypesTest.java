package pl.skyblock.miniony;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Item;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import pl.skyblock.miniony.model.Minion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Praca pozostałych typów minionów. */
class TypesTest extends TestSupport {

    private Minion place(String type) {
        PlayerMock player = player("Gracz");
        ItemStack item = give(player, type, 1).getFirst();
        player.getInventory().clear();
        placeOn(player, item, ground(0, 64, 0));
        return plugin.minions().get(plugin.items().readId(item));
    }

    private void cycle(Minion minion) {
        minion.setNextWorkAt(0);
        server.getScheduler().performTicks(plugin.settings().workTickPeriod());
    }

    private int stored(Minion minion, Material material) {
        int total = 0;
        for (int i = 0; i < minion.storage().size(); i++) {
            ItemStack stack = minion.storage().get(i);
            if (stack != null && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    @Test
    void collectorPicksUpItemsWithoutDuplication() {
        Minion minion = place("zbieracz");
        Item item = world.dropItem(new Location(world, 1.5, 65, 1.5), new ItemStack(Material.DIAMOND, 7));
        item.setPickupDelay(0);
        cycle(minion);
        assertEquals(7, stored(minion, Material.DIAMOND), "zebrane do magazynu");
        assertTrue(item.isDead() || !item.isValid(), "przedmiot zniknął z ziemi (brak kopii)");
    }

    @Test
    void collectorIgnoresItemsWithPickupDelayAndOwner() {
        Minion minion = place("zbieracz");
        Item shown = world.dropItem(new Location(world, 1.5, 65, 1.5), new ItemStack(Material.DIAMOND, 1));
        shown.setPickupDelay(Integer.MAX_VALUE); // np. przedmiot wystawowy
        Item owned = world.dropItem(new Location(world, 1.5, 65, 1.5), new ItemStack(Material.EMERALD, 1));
        owned.setPickupDelay(0);
        owned.setOwner(java.util.UUID.randomUUID());
        cycle(minion);
        assertEquals(0, stored(minion, Material.DIAMOND));
        assertEquals(0, stored(minion, Material.EMERALD));
    }

    @Test
    void lumberjackPlacesLogsOnGround() {
        Minion minion = place("drwal");
        // drwal pracuje na swoim poziomie (y=65) i potrzebuje stałego gruntu pod spodem
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                world.getBlockAt(x, 64, z).setType(Material.DIRT);
            }
        }
        cycle(minion);
        int logs = 0;
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (world.getBlockAt(x, 65, z).getType() == Material.OAK_LOG) {
                    logs++;
                }
            }
        }
        assertEquals(1, logs, "postawiony jeden pień");
    }

    @Test
    void farmerPlantsAndHarvests() {
        Minion minion = place("farmer");
        world.getBlockAt(1, 64, 0).setType(Material.FARMLAND);
        cycle(minion);
        assertEquals(Material.WHEAT, world.getBlockAt(1, 65, 0).getType(), "zasadzona pszenica");
        // dojrzała uprawa
        var block = world.getBlockAt(1, 65, 0);
        Ageable data = (Ageable) block.getBlockData();
        data.setAge(data.getMaximumAge());
        block.setBlockData(data);
        cycle(minion);
        assertTrue(stored(minion, Material.WHEAT) > 0, "zebrana pszenica");
        assertEquals(0, ((Ageable) world.getBlockAt(1, 65, 0).getBlockData()).getAge(), "posadzona ponownie");
    }

    @Test
    void slayerKillsListedMobAndSkipsNamed() {
        Minion minion = place("zabojca");
        Zombie named = world.spawn(new Location(world, 1.5, 65, 1.5), Zombie.class);
        named.customName(net.kyori.adventure.text.Component.text("Pupil"));
        cycle(minion);
        assertFalse(named.isDead(), "nazwany mob pominięty");
        Zombie zombie = world.spawn(new Location(world, 2.5, 65, 1.5), Zombie.class);
        cycle(minion);
        assertTrue(zombie.isDead(), "zombie zabity");
    }
}
