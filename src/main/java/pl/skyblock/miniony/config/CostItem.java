package pl.skyblock.miniony.config;

import org.bukkit.Material;

import java.util.Locale;

/**
 * Przedmiot w koszcie ulepszenia: zwykły materiał ("IRON_INGOT:32") albo przedmiot Nexo ("nexo:mithril:4").
 * Dokładnie jedno z pól material / nexoId jest ustawione.
 */
public record CostItem(Material material, String nexoId, int amount) {

    public static final int MAX_AMOUNT = 100_000;

    public boolean isNexo() {
        return nexoId != null;
    }

    /** Parsuje wpis kosztu; zwraca null i wypisuje ostrzeżenie przy błędzie. */
    static CostItem parse(ConfigReader reader, String path, String raw) {
        String text = raw == null ? "" : raw.trim();
        String[] parts = text.split(":");
        try {
            if (parts.length >= 2 && parts[0].equalsIgnoreCase("nexo")) {
                String id = parts[1].trim().toLowerCase(Locale.ROOT);
                int amount = parts.length >= 3 ? Integer.parseInt(parts[2].trim()) : 1;
                if (id.isEmpty() || parts.length > 3) {
                    throw new IllegalArgumentException();
                }
                return validAmount(reader, path, raw, amount) ? new CostItem(null, id, amount) : null;
            }
            if (parts.length > 2 || parts[0].isBlank()) {
                throw new IllegalArgumentException();
            }
            Material material = Material.matchMaterial(parts[0].trim());
            if (material == null || material.isAir() || !material.isItem()) {
                reader.warn("Nieznany przedmiot '" + parts[0] + "' w koszcie '" + path + "'.");
                return null;
            }
            int amount = parts.length == 2 ? Integer.parseInt(parts[1].trim()) : 1;
            return validAmount(reader, path, raw, amount) ? new CostItem(material, null, amount) : null;
        } catch (IllegalArgumentException e) {
            reader.warn("Nieprawidłowy wpis kosztu '" + raw + "' w '" + path
                    + "'. Format: MATERIAL:ilość albo nexo:id:ilość.");
            return null;
        }
    }

    private static boolean validAmount(ConfigReader reader, String path, String raw, int amount) {
        if (amount < 1 || amount > MAX_AMOUNT) {
            reader.warn("Nieprawidłowa ilość w koszcie '" + raw + "' w '" + path + "' (1-" + MAX_AMOUNT + ").");
            return false;
        }
        return true;
    }
}
