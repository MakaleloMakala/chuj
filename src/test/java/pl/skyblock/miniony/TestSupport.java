package pl.skyblock.miniony;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayList;
import java.util.List;

/** Wspólna konfiguracja testów: atrapa serwera, świat, plugin i pomocnicze akcje gracza. */
abstract class TestSupport {

    protected ServerMock server;
    protected MinionyPlugin plugin;
    protected WorldMock world;

    @BeforeEach
    void setUpServer() {
        server = MockBukkit.mock(new TestServer());
        world = new TestWorld();
        server.addWorld(world);
        plugin = MockBukkit.load(MinionyPlugin.class);
        // MockBukkit nie implementuje encji Display (hologram) - w testach hologram jest wyłączony.
        setConfig("hologram.wlaczony", false);
    }

    /** Zmienia wartość w config.yml pluginu i przeładowuje go (/minion reload). */
    protected void setConfig(String path, Object value) {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "config.yml");
        org.bukkit.configuration.file.YamlConfiguration yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        yaml.set(path, value);
        try {
            yaml.save(file);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        if (!plugin.reloadPlugin()) {
            throw new IllegalStateException("reload nie powiódł się");
        }
    }

    @AfterEach
    void tearDownServer() {
        MockBukkit.unmock();
    }

    /** Gracz bez OP (podlega limitom). */
    protected PlayerMock player(String name) {
        PlayerMock player = new TestPlayer(server, name);
        server.addPlayer(player);
        player.setOp(false);
        return player;
    }

    /** Gracz testowy - saveData() (zapis ekwipunku na dysk) nie jest zaimplementowany w MockBukkit. */
    static final class TestPlayer extends PlayerMock {
        TestPlayer(ServerMock server, String name) {
            super(server, name, java.util.UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        int saves;

        @Override
        public void saveData() {
            saves++;
        }
    }

    /** Daje graczowi miniony komendą z konsoli i zwraca je (w kolejności slotów). */
    protected List<ItemStack> give(Player player, String type, int amount, String... extra) {
        String[] args = new String[4 + extra.length];
        args[0] = "give";
        args[1] = player.getName();
        args[2] = type;
        args[3] = String.valueOf(amount);
        System.arraycopy(extra, 0, args, 4, extra.length);
        server.executeConsole("minion", args);
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (plugin.items().isMinionItem(item)) {
                result.add(item.clone());
            }
        }
        return result;
    }

    /** Blok podłoża, na którym gracz stawia miniona (minion stanie nad nim). */
    protected Block ground(int x, int y, int z) {
        // gracz stoi obok - chunk miniona i sąsiednie są załadowane
        for (int cx = (x >> 4) - 1; cx <= (x >> 4) + 1; cx++) {
            for (int cz = (z >> 4) - 1; cz <= (z >> 4) + 1; cz++) {
                world.getChunkAt(cx, cz).load();
            }
        }
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.STONE);
        world.getBlockAt(x, y + 1, z).setType(Material.AIR);
        world.getBlockAt(x, y + 2, z).setType(Material.AIR);
        return block;
    }

    /** Symuluje prawy klik przedmiotem miniona w górną ścianę bloku. */
    protected PlayerInteractEvent placeOn(Player player, ItemStack item, Block clicked) {
        player.getInventory().setItemInMainHand(item);
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
                player.getInventory().getItemInMainHand(), clicked, BlockFace.UP, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        return event;
    }

    /** Wyładowanie chunka z wywołaniem zdarzenia (MockBukkit sam go nie wywołuje). */
    protected void unloadChunk(int cx, int cz) {
        org.bukkit.Chunk chunk = world.getChunkAt(cx, cz);
        server.getPluginManager().callEvent(new org.bukkit.event.world.ChunkUnloadEvent(chunk));
        chunk.unload();
        server.getScheduler().performOneTick();
    }

    /** Załadowanie chunka z wywołaniem zdarzenia (aktywacja minionów następuje tick później). */
    protected void loadChunk(int cx, int cz) {
        org.bukkit.Chunk chunk = world.getChunkAt(cx, cz);
        chunk.load();
        server.getPluginManager().callEvent(new org.bukkit.event.world.ChunkLoadEvent(chunk, false));
        server.getScheduler().performTicks(2);
    }

    protected long minionEntities() {
        long count = 0;
        for (Entity entity : world.getEntities()) {
            if (plugin.visuals().isMinionEntity(entity)) {
                count++;
            }
        }
        return count;
    }

    protected ArmorStand bodyOf(pl.skyblock.miniony.model.Minion minion) {
        return minion.body();
    }

    protected int countMinionItems(Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (plugin.items().isMinionItem(item)) {
                count++;
            }
        }
        return count;
    }

    protected int count(Player player, Material material) {
        int count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }
        return count;
    }
}
