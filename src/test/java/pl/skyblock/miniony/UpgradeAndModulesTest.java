package pl.skyblock.miniony;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import pl.skyblock.miniony.config.ModuleKind;
import pl.skyblock.miniony.model.EffectiveStats;
import pl.skyblock.miniony.model.Minion;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ulepszenia poziomu i rzadkości, limity rzadkości, moduły. */
class UpgradeAndModulesTest extends TestSupport {

    private Minion place(PlayerMock player, String type, String... extra) {
        ItemStack item = give(player, type, 1, extra).getFirst();
        player.getInventory().removeItem(item);
        placeOn(player, item, ground(plugin.minions().placedCount(player.getUniqueId()) * 6, 64, 0));
        ItemStack left = player.getInventory().getItemInMainHand();
        player.getInventory().setItemInMainHand(null);
        if (plugin.items().isMinionItem(left)) {
            player.getInventory().addItem(left); // odrzucony - wraca do ekwipunku
        }
        return plugin.minions().get(plugin.items().readId(item));
    }

    private void freeUpgradeMoney() {
        for (int level = 2; level <= 5; level++) {
            setConfig("typy.gornik.poziomy." + level + ".koszt.pieniadze", 0);
        }
    }

    @Test
    void upgradeTakesItemCostAndChangesStats() {
        freeUpgradeMoney();
        PlayerMock player = player("Gracz");
        Minion minion = place(player, "gornik");
        player.getInventory().addItem(new ItemStack(Material.COBBLESTONE, 64), new ItemStack(Material.COBBLESTONE, 64),
                new ItemStack(Material.COBBLESTONE, 64), new ItemStack(Material.COBBLESTONE, 64), new ItemStack(Material.COBBLESTONE, 10));

        plugin.upgrades().upgrade(player, minion);

        assertEquals(2, minion.level(), "poziom 2");
        assertEquals(10, count(player, Material.COBBLESTONE), "pobrano dokładnie 256 bruku");
        assertEquals(18, minion.storage().capacity(), "pojemność z poziomu 2");
        assertEquals(13_000L, plugin.minions().stats(minion).intervalMs(), "interwał z poziomu 2");
    }

    @Test
    void upgradeWithoutResourcesChangesNothing() {
        freeUpgradeMoney();
        PlayerMock player = player("Gracz");
        Minion minion = place(player, "gornik");
        player.getInventory().addItem(new ItemStack(Material.COBBLESTONE, 100));
        plugin.upgrades().upgrade(player, minion);
        assertEquals(1, minion.level(), "brak ulepszenia");
        assertEquals(100, count(player, Material.COBBLESTONE), "nic nie pobrano");
    }

    @Test
    void renamedItemsDoNotCountAsCost() {
        freeUpgradeMoney();
        PlayerMock player = player("Gracz");
        Minion minion = place(player, "gornik");
        for (int i = 0; i < 4; i++) {
            ItemStack renamed = new ItemStack(Material.COBBLESTONE, 64);
            ItemMeta meta = renamed.getItemMeta();
            meta.displayName(Component.text("Specjalny bruk"));
            renamed.setItemMeta(meta);
            player.getInventory().addItem(renamed);
        }
        plugin.upgrades().upgrade(player, minion);
        assertEquals(1, minion.level(), "przemianowane przedmioty nie liczą się do kosztu");
        assertEquals(256, count(player, Material.COBBLESTONE), "nic nie pobrano");
    }

    @Test
    void moneyCostWithoutVaultIsBlocked() {
        PlayerMock player = player("Gracz"); // domyślny koszt poziomu 2: 5000 + 256 bruku, Vault nie istnieje
        Minion minion = place(player, "gornik");
        player.getInventory().addItem(new ItemStack(Material.COBBLESTONE, 64), new ItemStack(Material.COBBLESTONE, 64),
                new ItemStack(Material.COBBLESTONE, 64), new ItemStack(Material.COBBLESTONE, 64));
        plugin.upgrades().upgrade(player, minion);
        assertEquals(1, minion.level(), "bez ekonomii ulepszenie zablokowane");
        assertEquals(256, count(player, Material.COBBLESTONE), "przedmioty nietknięte");
    }

    @Test
    void upgradeClickSpamUpgradesOnce() {
        freeUpgradeMoney();
        PlayerMock player = player("Gracz");
        Minion minion = place(player, "gornik");
        for (int i = 0; i < 10; i++) {
            player.getInventory().addItem(new ItemStack(Material.COBBLESTONE, 64), new ItemStack(Material.IRON_INGOT, 64));
        }
        server.getPluginManager().callEvent(new PlayerInteractAtEntityEvent(player, minion.body(), new Vector(), EquipmentSlot.HAND));
        int slot = plugin.settings().gui().upgrade().slot();
        for (int i = 0; i < 5; i++) {
            player.simulateInventoryClick(player.getOpenInventory(), ClickType.LEFT, slot);
        }
        server.getScheduler().performOneTick();
        assertEquals(2, minion.level(), "spam kliknięć = jedno ulepszenie (blokada 1 s)");
    }

    @Test
    void cannotUpgradeOthersMinion() {
        freeUpgradeMoney();
        PlayerMock owner = player("Wlasciciel");
        PlayerMock other = player("Obcy");
        Minion minion = place(owner, "gornik");
        other.getInventory().addItem(new ItemStack(Material.COBBLESTONE, 64), new ItemStack(Material.COBBLESTONE, 64),
                new ItemStack(Material.COBBLESTONE, 64), new ItemStack(Material.COBBLESTONE, 64));
        plugin.upgrades().upgrade(other, minion);
        assertEquals(1, minion.level());
        assertEquals(256, count(other, Material.COBBLESTONE));
    }

    @Test
    void rarityMultipliersApply() {
        PlayerMock player = player("Gracz");
        Minion minion = place(player, "gornik", "1", "EPICKI");
        EffectiveStats stats = plugin.minions().stats(minion);
        assertEquals(12_000L, stats.intervalMs(), "15 s / 1.25");
        assertEquals(14, stats.capacity(), "9 x 1.5 = 13.5 -> 14");
        assertEquals(3, stats.moduleSlots());
        assertEquals(1.25, stats.dropMultiplier(), 1e-9);
    }

    @Test
    void rarityLimitIsEnforced() {
        PlayerMock player = player("Gracz");
        player.addAttachment(plugin, "miniony.limit.10", true);
        plugin.limits().refresh(player);
        for (int i = 0; i < 4; i++) {
            place(player, "gornik", "1", "LEGENDARNY");
        }
        assertEquals(3, plugin.minions().placedCount(player.getUniqueId(), "LEGENDARNY"), "limit LEGENDARNY = 3");
        assertEquals(1, countMinionItems(player), "4. legendarny został w ekwipunku");
    }

    @Test
    void rarityUpgrade() {
        setConfig("ulepszanie-rzadkosci.wlaczone", true);
        setConfig("ulepszanie-rzadkosci.wymaga-max-poziomu", false);
        setConfig("rzadkosci.RZADKI.koszt-ulepszenia.pieniadze", 0);
        setConfig("rzadkosci.RZADKI.koszt-ulepszenia.przedmioty", List.of("DIAMOND:2"));
        PlayerMock player = player("Gracz");
        Minion minion = place(player, "gornik");
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 3));
        plugin.upgrades().upgradeRarity(player, minion);
        assertEquals("RZADKI", minion.rarity());
        assertEquals(1, count(player, Material.DIAMOND));
        assertEquals(2, plugin.minions().stats(minion).moduleSlots());
    }

    private void freeModules() {
        for (ModuleKind kind : ModuleKind.values()) {
            setConfig("moduly." + kind.id() + ".koszt.pieniadze", 0);
            setConfig("moduly." + kind.id() + ".koszt.przedmioty", List.of());
        }
    }

    @Test
    void moduleSlotsAreLimitedByRarity() {
        freeModules();
        PlayerMock player = player("Gracz");
        Minion minion = place(player, "gornik"); // ZWYKLY: 1 slot
        plugin.modules().install(player, minion, ModuleKind.COMPACTOR);
        plugin.upgrades().forget(player.getUniqueId()); // pomiń blokadę 1 s między płatnymi akcjami
        plugin.modules().install(player, minion, ModuleKind.RANGE);
        assertEquals(List.of("kompaktor"), minion.modules(), "drugi moduł nie zmieścił się");
    }

    @Test
    void compactorAndRangeModules() {
        freeModules();
        PlayerMock player = player("Gracz");
        Minion minion = place(player, "gornik", "1", "RZADKI"); // 2 sloty
        plugin.modules().install(player, minion, ModuleKind.COMPACTOR);
        plugin.upgrades().forget(player.getUniqueId());
        int rangeBefore = plugin.minions().stats(minion).range();
        plugin.modules().install(player, minion, ModuleKind.RANGE);
        assertEquals(rangeBefore + 1, plugin.minions().stats(minion).range(), "zasięg + 1");

        minion.storage().add(List.of(new ItemStack(Material.IRON_INGOT, 20)));
        plugin.modules().afterWork(minion, world, System.currentTimeMillis());
        int blocks = 0;
        int ingots = 0;
        for (int i = 0; i < minion.storage().size(); i++) {
            ItemStack stack = minion.storage().get(i);
            if (stack == null) {
                continue;
            }
            if (stack.getType() == Material.IRON_BLOCK) {
                blocks += stack.getAmount();
            } else if (stack.getType() == Material.IRON_INGOT) {
                ingots += stack.getAmount();
            }
        }
        assertEquals(2, blocks, "20 sztabek -> 2 bloki");
        assertEquals(2, ingots, "reszta 2 sztabki");
    }

    @Test
    void fuelSpeedsUpAndOtherFuelIsRejected() {
        freeModules();
        PlayerMock player = player("Gracz");
        Minion minion = place(player, "gornik");
        plugin.modules().install(player, minion, ModuleKind.FUEL);
        player.getInventory().addItem(new ItemStack(Material.BLAZE_ROD, 2), new ItemStack(Material.COAL, 5));
        long before = plugin.minions().stats(minion).intervalMs();
        plugin.modules().addFuel(player, minion, false);
        assertTrue(minion.fuelMs() > 0, "paliwo dolane");
        assertTrue(plugin.minions().stats(minion).intervalMs() < before, "minion pracuje szybciej");
        String type = minion.fuelType();
        plugin.modules().addFuel(player, minion, true);
        assertEquals(type, minion.fuelType(), "nie zmieszano rodzajów paliwa");
    }

    @Test
    void autoSellWithoutVaultKeepsItems() {
        freeModules();
        PlayerMock player = player("Gracz");
        Minion minion = place(player, "gornik");
        plugin.modules().install(player, minion, ModuleKind.AUTO_SELL);
        minion.storage().add(List.of(new ItemStack(Material.COBBLESTONE, 64)));
        plugin.modules().afterWork(minion, world, System.currentTimeMillis() + 3_600_000L);
        assertFalse(minion.storage().isEmpty(), "bez ekonomii nic nie znika");
    }
}
