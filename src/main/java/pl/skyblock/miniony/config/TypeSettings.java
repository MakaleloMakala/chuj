package pl.skyblock.miniony.config;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Wspólne ustawienia typu miniona (sekcja typy.&lt;id&gt;) wraz z poziomami 1..N.
 * Wartości poziomu, których nie ma w configu, są dziedziczone z poprzedniego poziomu.
 */
public record TypeSettings(
        String id,
        boolean enabled,
        String displayName,
        Material itemMaterial,
        String itemName,
        List<String> itemLore,
        List<LevelSettings> levels,
        OfflineDrops offline
) {

    public static final int MAX_CAPACITY = 270;
    public static final int MAX_LEVELS = 100;

    public int maxLevel() {
        return levels.size();
    }

    /** Ustawienia poziomu (poziom spoza zakresu jest przycinany do 1..max). */
    public LevelSettings level(int level) {
        int index = Math.max(1, Math.min(level, levels.size())) - 1;
        return levels.get(index);
    }

    /** Ustawienia domyślne (typ wyłączony) - używane, gdy w configu brak sekcji typu. */
    public static TypeSettings defaults(String id, String name, Appearance appearance) {
        LevelSettings level = new LevelSettings(1, 15, 9, 2, 1.0, UpgradeCost.FREE, appearance);
        return new TypeSettings(id, false, name, Material.PLAYER_HEAD, "<gold>Minion: <typ>", List.of(), List.of(level),
                OfflineDrops.DISABLED);
    }

    /** Czyta ustawienia typu. Domyślna nazwa i wygląd pochodzą z klasy typu. */
    public static TypeSettings read(ConfigReader reader, String id, String defName, Appearance defAppearance) {
        String base = "typy." + id + ".";
        boolean enabled = reader.getBoolean(base + "wlaczony", true);
        String name = reader.getString(base + "nazwa", defName);
        Material itemMaterial = reader.getMaterial(base + "item.material", Material.PLAYER_HEAD, true, false);
        String itemName = reader.getString(base + "item.nazwa", "<gold>Minion: <typ> <gray>[Poz. <poziom>]");
        List<String> lore = reader.getStringList(base + "item.lore", List.of("<gray>Rzadkość: <rzadkosc>"));
        return new TypeSettings(id, enabled, name, itemMaterial, itemName, lore,
                readLevels(reader, base, defAppearance), OfflineDrops.read(reader, base));
    }

    private static List<LevelSettings> readLevels(ConfigReader reader, String base, Appearance defAppearance) {
        // Poziom "zerowy" = wartości z poziomu typu (zgodność z configiem z Etapu 1) albo stałe domyślne.
        LevelSettings previous = new LevelSettings(0,
                reader.optInt(base + "interwal-sekundy", 15, 1, 86_400),
                reader.optInt(base + "pojemnosc", 9, 1, MAX_CAPACITY),
                reader.optInt(base + "zasieg", 2, 1, 16),
                reader.optDouble(base + "mnoznik-dropu", 1.0, 0.0, 100.0),
                UpgradeCost.FREE,
                Appearance.read(reader, base + "wyglad", defAppearance));

        String path = base + "poziomy";
        ConfigReader source = reader;
        if (!reader.hasInFile(path)) {
            ConfigReader defaults = reader.defaultsReader();
            if (defaults != null && defaults.isSection(path)) {
                reader.warn("Brak sekcji '" + path + "' w config.yml. Używam domyślnych poziomów.");
                source = defaults;
            } else {
                reader.warn("Brak sekcji '" + path + "' - typ ma tylko poziom 1.");
                return List.of(withLevel(previous, 1));
            }
        }
        Map<Integer, String> sorted = new TreeMap<>();
        Set<String> keys = source.keys(path);
        for (String key : keys) {
            try {
                int level = Integer.parseInt(key.trim());
                if (level < 1 || level > MAX_LEVELS) {
                    throw new NumberFormatException();
                }
                sorted.put(level, key);
            } catch (NumberFormatException e) {
                reader.warn("Nieprawidłowy numer poziomu '" + key + "' w '" + path + "' (1-" + MAX_LEVELS + ") - pomijam.");
            }
        }
        List<LevelSettings> levels = new ArrayList<>();
        int expected = 1;
        for (Map.Entry<Integer, String> entry : sorted.entrySet()) {
            if (entry.getKey() != expected) {
                reader.warn("Brak poziomu " + expected + " w '" + path + "' - poziomy od " + entry.getKey() + " wzwyż są pomijane.");
                break;
            }
            String p = path + "." + entry.getValue();
            int interval = source.optInt(p + ".interwal-sekundy", previous.intervalSeconds(), 1, 86_400);
            int capacity = source.optInt(p + ".pojemnosc", previous.capacity(), 1, MAX_CAPACITY);
            int range = source.optInt(p + ".zasieg", previous.range(), 1, 16);
            double multiplier = source.optDouble(p + ".mnoznik-dropu", previous.dropMultiplier(), 0.0, 100.0);
            Appearance appearance = Appearance.read(source, p + ".wyglad", previous.appearance());
            UpgradeCost cost = expected == 1 ? UpgradeCost.FREE : UpgradeCost.read(source, p + ".koszt");
            LevelSettings level = new LevelSettings(expected, interval, capacity, range, multiplier, cost, appearance);
            levels.add(level);
            previous = level;
            expected++;
        }
        if (levels.isEmpty()) {
            reader.warn("Sekcja '" + path + "' nie zawiera poprawnych poziomów - typ ma tylko poziom 1.");
            levels.add(withLevel(previous, 1));
        }
        return List.copyOf(levels);
    }

    private static LevelSettings withLevel(LevelSettings s, int level) {
        return new LevelSettings(level, s.intervalSeconds(), s.capacity(), s.range(), s.dropMultiplier(),
                UpgradeCost.FREE, s.appearance());
    }
}
