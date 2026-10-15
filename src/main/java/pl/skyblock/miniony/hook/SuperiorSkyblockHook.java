package pl.skyblock.miniony.hook;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.events.IslandDisbandEvent;
import com.bgsoftware.superiorskyblock.api.events.IslandKickEvent;
import com.bgsoftware.superiorskyblock.api.events.IslandQuitEvent;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import pl.skyblock.miniony.MinionyPlugin;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Integracja z SuperiorSkyblock2. Ta klasa jest ładowana tylko wtedy, gdy SSB2 jest włączony,
 * więc brak pluginu nie powoduje NoClassDefFoundError.
 */
public final class SuperiorSkyblockHook implements IslandHook, Listener {

    private final MinionyPlugin plugin;

    public SuperiorSkyblockHook(MinionyPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public UUID islandAt(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        try {
            Island island = SuperiorSkyblockAPI.getIslandAt(location);
            return island == null ? null : island.getUniqueId();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Błąd SSB2 (getIslandAt)", e);
            return null;
        }
    }

    @Override
    public boolean isMember(Player player, UUID islandId) {
        if (player == null || islandId == null) {
            return false;
        }
        try {
            Island island = SuperiorSkyblockAPI.getIslandByUUID(islandId);
            SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(player);
            return island != null && superiorPlayer != null && island.isMember(superiorPlayer);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Błąd SSB2 (isMember)", e);
            return false;
        }
    }

    @Override
    public boolean isInsideIsland(UUID islandId, Location location) {
        if (islandId == null) {
            return true;
        }
        UUID at = islandAt(location);
        return islandId.equals(at);
    }

    @Override
    public void blockPlaced(Block block) {
        if (!plugin.settings().updateIslandBlocks()) {
            return;
        }
        try {
            Island island = SuperiorSkyblockAPI.getIslandAt(block.getLocation());
            if (island != null) {
                island.handleBlockPlace(block);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Błąd SSB2 (handleBlockPlace)", e);
        }
    }

    @Override
    public void blockBroken(Block block) {
        if (!plugin.settings().updateIslandBlocks()) {
            return;
        }
        try {
            Island island = SuperiorSkyblockAPI.getIslandAt(block.getLocation());
            if (island != null) {
                island.handleBlockBreak(block);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Błąd SSB2 (handleBlockBreak)", e);
        }
    }

    @Override
    public void shutdown() {
        HandlerList.unregisterAll(this);
    }

    // --- Zdarzenia wysp: usunięcie wyspy / wyrzucenie / opuszczenie wyspy zwraca miniony właścicielom ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDisband(IslandDisbandEvent event) {
        Island island = event.getIsland();
        if (island != null) {
            plugin.minions().returnIslandMinions(island.getUniqueId(), null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(IslandKickEvent event) {
        Island island = event.getIsland();
        SuperiorPlayer target = event.getTarget();
        if (island != null && target != null) {
            plugin.minions().returnIslandMinions(island.getUniqueId(), target.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(IslandQuitEvent event) {
        Island island = event.getIsland();
        SuperiorPlayer player = event.getPlayer();
        if (island != null && player != null) {
            plugin.minions().returnIslandMinions(island.getUniqueId(), player.getUniqueId());
        }
    }
}
