package pl.skyblock.miniony.limit;

import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import pl.skyblock.miniony.MinionyPlugin;
import pl.skyblock.miniony.config.PluginSettings;
import pl.skyblock.miniony.hook.LuckPermsHook;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Limity postawionych minionów na gracza (liczone po właścicielu).
 * <ul>
 *     <li>permisja {@code miniony.limit.<liczba>} - bierzemy NAJWIĘKSZĄ,</li>
 *     <li>brak permisji - limit domyślny z configu,</li>
 *     <li>{@code miniony.limit.bypass} - bez limitu,</li>
 *     <li>twardy sufit {@code limity.maksimum}.</li>
 * </ul>
 * Wynik jest cache'owany i odświeżany przy wejściu gracza i przy UserDataRecalculateEvent (LuckPerms).
 */
public final class LimitService {

    public static final int UNLIMITED = Integer.MAX_VALUE;
    private static final String PREFIX = "miniony.limit.";
    public static final String BYPASS = "miniony.limit.bypass";

    private final MinionyPlugin plugin;
    private final Map<UUID, Integer> cache = new HashMap<>();
    private LuckPermsHook luckPerms;

    public LimitService(MinionyPlugin plugin) {
        this.plugin = plugin;
    }

    public void setLuckPerms(LuckPermsHook luckPerms) {
        this.luckPerms = luckPerms;
    }

    /** Limit gracza (z cache albo wyliczony). Tylko główny wątek. */
    public int limit(Player player) {
        Integer cached = cache.get(player.getUniqueId());
        if (cached != null) {
            return cached;
        }
        int value = compute(player);
        cache.put(player.getUniqueId(), value);
        return value;
    }

    public void refresh(Player player) {
        cache.put(player.getUniqueId(), compute(player));
    }

    public void invalidate(UUID uuid) {
        cache.remove(uuid);
    }

    public void clear() {
        cache.clear();
    }

    private int compute(Player player) {
        PluginSettings settings = plugin.settings();
        if (player.hasPermission(BYPASS)) {
            return settings.bypassIgnoresMax() ? UNLIMITED : settings.maxLimit();
        }
        int best = -1;
        Map<String, Boolean> lpPermissions = luckPerms == null ? null : luckPerms.permissions(player);
        if (lpPermissions != null) {
            for (Map.Entry<String, Boolean> entry : lpPermissions.entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue())) {
                    best = Math.max(best, parse(entry.getKey()));
                }
            }
        } else {
            for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
                if (info.getValue()) {
                    best = Math.max(best, parse(info.getPermission()));
                }
            }
        }
        int limit = best >= 0 ? best : settings.defaultLimit();
        return Math.min(limit, settings.maxLimit());
    }

    private static int parse(String permission) {
        if (permission == null) {
            return -1;
        }
        String lower = permission.toLowerCase(Locale.ROOT);
        if (!lower.startsWith(PREFIX)) {
            return -1;
        }
        String number = lower.substring(PREFIX.length());
        if (number.isEmpty() || number.length() > 7) {
            return -1;
        }
        for (int i = 0; i < number.length(); i++) {
            char c = number.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
        }
        return Integer.parseInt(number);
    }
}
