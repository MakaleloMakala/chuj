package pl.skyblock.miniony.hook;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import pl.skyblock.miniony.MinionyPlugin;

import java.util.Map;
import java.util.UUID;

/**
 * Integracja z LuckPerms: odczyt permisji limitów i odświeżanie cache przy zmianie uprawnień.
 * Ładowana tylko, gdy LuckPerms jest włączony.
 */
public final class LuckPermsHook {

    private final LuckPerms luckPerms;
    private final EventSubscription<UserDataRecalculateEvent> subscription;

    private LuckPermsHook(MinionyPlugin plugin, LuckPerms luckPerms) {
        this.luckPerms = luckPerms;
        this.subscription = luckPerms.getEventBus().subscribe(plugin, UserDataRecalculateEvent.class, event -> {
            UUID uuid = event.getUser().getUniqueId();
            // Zdarzenie może przyjść z dowolnego wątku - wracamy na główny wątek.
            if (!plugin.isEnabled()) {
                return;
            }
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null) {
                        plugin.limits().refresh(player);
                    }
                });
            } catch (RuntimeException ignored) {
                // plugin wyłączany w trakcie - nic do zrobienia
            }
        });
    }

    /** Zwraca hook albo null, gdy usługa LuckPerms nie jest dostępna. */
    public static LuckPermsHook create(MinionyPlugin plugin) {
        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider == null) {
            return null;
        }
        return new LuckPermsHook(plugin, provider.getProvider());
    }

    /** Mapa permisji gracza z LuckPerms albo null, gdy dane użytkownika nie są załadowane. */
    public Map<String, Boolean> permissions(Player player) {
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return null;
        }
        return user.getCachedData().getPermissionData().getPermissionMap();
    }

    public void shutdown() {
        subscription.close();
    }
}
