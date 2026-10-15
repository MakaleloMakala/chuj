package pl.skyblock.miniony.config;

import org.bukkit.Material;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drop używany przy symulacji pracy offline typu: co {@code cyclesPerDrop} cykli pracy minion
 * "produkuje" jedną jednostkę zawierającą {@code drops}.
 */
public record OfflineDrops(boolean enabled, int cyclesPerDrop, Map<Material, Integer> drops) {

    public static final OfflineDrops DISABLED = new OfflineDrops(false, 1, Map.of());

    static OfflineDrops read(ConfigReader reader, String base) {
        String p = base + "offline.";
        if (!reader.has(p + "wlaczona")) {
            return DISABLED;
        }
        boolean enabled = reader.getBoolean(p + "wlaczona", false);
        if (!enabled) {
            return DISABLED;
        }
        int cycles = reader.getInt(p + "cykle-na-drop", 2, 1, 10_000);
        Map<Material, Integer> drops = new LinkedHashMap<>();
        for (String raw : reader.getStringList(p + "drop", List.of())) {
            String[] parts = raw.trim().split(":");
            Material material = Material.matchMaterial(parts[0].trim());
            int amount = 1;
            boolean ok = material != null && material.isItem() && !material.isAir() && parts.length <= 2;
            if (ok && parts.length == 2) {
                try {
                    amount = Integer.parseInt(parts[1].trim());
                    ok = amount >= 1 && amount <= 64;
                } catch (NumberFormatException e) {
                    ok = false;
                }
            }
            if (!ok) {
                reader.warn("Nieprawidłowy wpis '" + raw + "' w '" + p + "drop' (format MATERIAL:ilość) - pomijam.");
                continue;
            }
            drops.merge(material, amount, Integer::sum);
        }
        return new OfflineDrops(true, cycles, Map.copyOf(drops));
    }
}
