package pl.skyblock.miniony;

import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import pl.skyblock.miniony.model.Minion;
import pl.skyblock.miniony.model.MinionState;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Praca w cyklach, chunki, restart pluginu, praca offline, zniszczony przedmiot. */
class WorkAndPersistenceTest extends TestSupport {

    private PlayerMock owner;
    private Minion minion;
    private UUID id;

    @BeforeEach
    void placeMinion() {
        owner = player("Wlasciciel");
        ItemStack item = give(owner, "gornik", 1).getFirst();
        owner.getInventory().clear();
        placeOn(owner, item, ground(0, 64, 0));
        id = plugin.items().readId(item);
        minion = plugin.minions().get(id);
    }

    /** Wymusza N cykli pracy (plugin liczy czas zegarem - w teście "przyspieszamy" czas). */
    private void workCycles(int cycles) {
        for (int i = 0; i < cycles; i++) {
            minion.setNextWorkAt(0);
            server.getScheduler().performTicks(plugin.settings().workTickPeriod());
        }
    }

    private int countAround(Material material) {
        int count = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (world.getBlockAt(dx, 64, dz).getType() == material) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    void minerPlacesBlocksOnlyOnLayerBelowAndNotUnderItself() {
        workCycles(10);
        assertEquals(10, countAround(Material.COBBLESTONE), "10 cykli = 10 postawionych bloków bruku");
        assertEquals(Material.STONE, world.getBlockAt(0, 64, 0).getType(), "blok pod minionem nietknięty");
        assertTrue(world.getBlockAt(3, 64, 0).getType().isAir(), "poza zasięgiem (2) nic nie stawia");
    }

    @Test
    void minerMinesWhenAreaIsFull() {
        workCycles(24); // 24 wolne miejsca w obszarze 5x5 (bez środka)
        assertEquals(24, countAround(Material.COBBLESTONE));
        workCycles(3);
        // cykle: wykop -> postaw w pustym miejscu -> wykop
        assertEquals(23, countAround(Material.COBBLESTONE), "naprzemiennie stawia i wykopuje");
        assertEquals(2, storedTotal(Material.COBBLESTONE), "2 bruki w magazynie");
    }

    @Test
    void storageFullStopsMining() {
        workCycles(24);
        minion.storage().add(java.util.Collections.nCopies(9, new ItemStack(Material.COBBLESTONE, 64)));
        workCycles(3);
        assertEquals(24, countAround(Material.COBBLESTONE), "przy pełnym magazynie nic nie wykopuje (nic nie ginie)");
        assertEquals(pl.skyblock.miniony.model.MinionStatus.STORAGE_FULL, minion.status());
    }

    @Test
    void blockWithoutDropsIsNotDestroyed() {
        setConfig("typy.gornik.blok", "OBSIDIAN");
        minion = plugin.minions().get(id);
        workCycles(24);
        assertEquals(24, countAround(Material.OBSIDIAN));
        workCycles(3);
        assertEquals(24, countAround(Material.OBSIDIAN), "obsydian bez dropu nie jest niszczony");
    }

    private int storedTotal(Material material) {
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
    void chunkUnloadRemovesEntitiesAndReloadRestoresThem() {
        assertNotNull(minion.body());
        unloadChunk(0, 0);
        assertNull(minion.body(), "encja usunięta przy wyładowaniu chunka");
        assertEquals(0, minionEntities());
        loadChunk(0, 0);
        assertNotNull(minion.body(), "encja odtworzona po załadowaniu");
        assertEquals(1, minionEntities(), "bez duplikatów");
    }

    @Test
    void restartKeepsDataAndDoesNotDuplicateEntities() {
        minion.storage().add(List.of(new ItemStack(Material.DIAMOND, 5)));
        plugin.minions().storageChangedByPlayer(minion);

        server.getPluginManager().disablePlugin(plugin);
        assertEquals(0, minionEntities(), "po wyłączeniu encje usunięte");
        server.getPluginManager().enablePlugin(plugin);
        server.getScheduler().performTicks(2);

        Minion reloaded = plugin.minions().get(id);
        assertNotNull(reloaded, "minion wczytany z bazy");
        assertEquals(MinionState.PLACED, reloaded.state());
        assertEquals(owner.getUniqueId(), reloaded.owner());
        int diamonds = 0;
        for (int i = 0; i < reloaded.storage().size(); i++) {
            ItemStack stack = reloaded.storage().get(i);
            if (stack != null && stack.getType() == Material.DIAMOND) {
                diamonds += stack.getAmount();
            }
        }
        assertEquals(5, diamonds, "magazyn zachowany");
        assertEquals(1, minionEntities(), "dokładnie jedna encja po restarcie");
        assertEquals(1, plugin.minions().placedCount(owner.getUniqueId()), "licznik limitu odtworzony");
    }

    @Test
    void offlineProductionAfterChunkReload() {
        unloadChunk(0, 0);
        // minion był wyładowany przez godzinę
        minion.setLastActiveAt(System.currentTimeMillis() - 3_600_000L);
        loadChunk(0, 0);
        int cobble = 0;
        for (int i = 0; i < minion.storage().size(); i++) {
            ItemStack stack = minion.storage().get(i);
            if (stack != null && stack.getType() == Material.COBBLESTONE) {
                cobble += stack.getAmount();
            }
        }
        // poziom 1: co 15 s, co 2 cykle 1 bruk -> 3600/15/2 = 120 (magazyn 9 slotów mieści 576)
        assertEquals(120, cobble, "produkcja offline za godzinę");
        assertEquals(0, minion.lastActiveAt(), "znacznik wyzerowany - produkcja nie powtórzy się");
    }

    @Test
    void offlineProductionIsCappedByMaxHours() {
        unloadChunk(0, 0);
        minion.setLastActiveAt(System.currentTimeMillis() - 100L * 3_600_000L); // 100 h, limit 12 h
        loadChunk(0, 0);
        int cobble = 0;
        for (int i = 0; i < minion.storage().size(); i++) {
            ItemStack stack = minion.storage().get(i);
            if (stack != null) {
                cobble += stack.getAmount();
            }
        }
        assertEquals(9 * 64, cobble, "magazyn pełny (12 h = 1440 szt. > 576), nadmiar przepada");
    }

    @Test
    void destroyedItemRemovesMinion() {
        plugin.minions().pickup(owner, minion);
        ItemStack item = null;
        for (ItemStack stack : owner.getInventory().getStorageContents()) {
            if (plugin.items().isMinionItem(stack)) {
                item = stack;
            }
        }
        assertNotNull(item);
        Item dropped = world.dropItem(owner.getLocation(), item);
        server.getPluginManager().callEvent(new EntityRemoveEvent(dropped, EntityRemoveEvent.Cause.DEATH)); // np. lawa
        assertNull(plugin.minions().get(id), "minion usunięty po zniszczeniu przedmiotu");
    }

    @Test
    void mergedItemDoesNotRemoveMinion() {
        plugin.minions().pickup(owner, minion);
        ItemStack item = null;
        for (ItemStack stack : owner.getInventory().getStorageContents()) {
            if (plugin.items().isMinionItem(stack)) {
                item = stack;
            }
        }
        Item dropped = world.dropItem(owner.getLocation(), item);
        server.getPluginManager().callEvent(new EntityRemoveEvent(dropped, EntityRemoveEvent.Cause.UNLOAD));
        server.getPluginManager().callEvent(new EntityRemoveEvent(dropped, EntityRemoveEvent.Cause.PICKUP));
        assertNotNull(plugin.minions().get(id), "wyładowanie chunka/podniesienie nie usuwa miniona");
    }
}
