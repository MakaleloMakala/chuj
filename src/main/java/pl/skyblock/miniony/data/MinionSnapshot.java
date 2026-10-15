package pl.skyblock.miniony.data;

import java.util.UUID;

/**
 * Niezmienna kopia danych miniona przygotowana na głównym wątku i zapisywana asynchronicznie.
 * Nie zawiera żadnych obiektów Bukkit - magazyn jest już zserializowany do bajtów.
 */
public record MinionSnapshot(
        UUID id,
        UUID owner,
        String ownerName,
        UUID island,
        String type,
        String rarity,
        int level,
        String state,
        UUID token,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        byte[] storage,
        String modules,
        boolean pendingDelivery,
        long createdAt,
        long lastWorkAt
) {
}
