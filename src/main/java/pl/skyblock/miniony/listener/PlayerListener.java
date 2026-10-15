package pl.skyblock.miniony.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import pl.skyblock.miniony.MinionyPlugin;

/** Odświeżanie cache limitów, nicków właścicieli i wydawanie minionów oczekujących na zwrot. */
public final class PlayerListener implements Listener {

    private final MinionyPlugin plugin;

    public PlayerListener(MinionyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.limits().refresh(player);
        plugin.minions().updateOwnerName(player);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                plugin.minions().deliverPending(player, false);
            }
        }, 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.limits().invalidate(event.getPlayer().getUniqueId());
    }
}
