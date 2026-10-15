package pl.skyblock.miniony.config;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Bezpieczny odczyt wartości z configu. Każda brakująca lub błędna wartość
 * daje czytelny komunikat w konsoli i bezpieczną wartość domyślną zamiast wyjątku.
 * <p>
 * Metody {@code getX} - wartość wymagana (brak = ostrzeżenie + domyślna z jara lub z kodu).<br>
 * Metody {@code optX} - wartość opcjonalna, czytana tylko jeśli jest jawnie w pliku (brak = wartość odziedziczona, bez ostrzeżenia).
 */
public final class ConfigReader {

    private final ConfigurationSection root;
    private final Logger logger;
    private final int[] warnings;

    public ConfigReader(ConfigurationSection root, Logger logger) {
        this(root, logger, new int[1]);
    }

    private ConfigReader(ConfigurationSection root, Logger logger, int[] warnings) {
        this.root = root;
        this.logger = logger;
        this.warnings = warnings;
    }

    public ConfigurationSection root() {
        return root;
    }

    public int warnings() {
        return warnings[0];
    }

    public void warn(String message) {
        warnings[0]++;
        logger.warning("[Konfiguracja] " + message);
    }

    /** Czytnik domyślnego configu z jara (licznik ostrzeżeń wspólny) albo null, gdy brak domyślnych. */
    public ConfigReader defaultsReader() {
        if (root instanceof Configuration configuration && configuration.getDefaults() != null) {
            return new ConfigReader(configuration.getDefaults(), logger, warnings);
        }
        return null;
    }

    private void invalid(String path, Object value, String expected, Object fallback) {
        warn("Nieprawidłowa wartość '" + value + "' w '" + path + "' (oczekiwano: " + expected + "). Używam: " + fallback);
    }

    private void missing(String path, Object fallback) {
        warn("Brak wartości '" + path + "'. Używam: " + fallback);
    }

    /** Czy wartość istnieje (w pliku albo w domyślnych). */
    public boolean has(String path) {
        return root.get(path) != null;
    }

    /** Czy wartość jest jawnie wpisana w pliku. */
    public boolean hasInFile(String path) {
        return root.contains(path, true);
    }

    /**
     * Klucze sekcji. Gdy sekcji nie ma w pliku, ale jest w domyślnym configu - ostrzeżenie i klucze domyślne.
     */
    public Set<String> keys(String path) {
        if (root.contains(path, true)) {
            ConfigurationSection section = root.getConfigurationSection(path);
            if (section == null) {
                warn("'" + path + "' powinno być sekcją.");
                return Set.of();
            }
            return new LinkedHashSet<>(section.getKeys(false));
        }
        if (root instanceof Configuration configuration && configuration.getDefaults() != null) {
            ConfigurationSection def = configuration.getDefaults().getConfigurationSection(path);
            if (def != null) {
                warn("Brak sekcji '" + path + "' w config.yml. Używam domyślnej.");
                return new LinkedHashSet<>(def.getKeys(false));
            }
        }
        return Set.of();
    }

    /** Czy sekcja istnieje (w pliku albo w domyślnych). */
    public boolean isSection(String path) {
        return root.isConfigurationSection(path);
    }

    /**
     * Wartość z configu. Jeżeli klucza nie ma w pliku użytkownika, ale jest w domyślnym configu z jara,
     * wypisujemy ostrzeżenie i używamy wartości domyślnej.
     */
    private Object value(String path) {
        Object value = root.get(path);
        if (value != null && !root.contains(path, true)) {
            warn("Brak wartości '" + path + "' w config.yml. Używam domyślnej: " + value);
        }
        return value;
    }

    private Object explicit(String path) {
        return root.contains(path, true) ? root.get(path) : null;
    }

    // ===================================================================== int

    public int getInt(String path, int def, int min, int max) {
        Object value = value(path);
        if (value == null) {
            missing(path, def);
            return def;
        }
        return parseInt(path, value, def, min, max);
    }

    public int optInt(String path, int inherited, int min, int max) {
        Object value = explicit(path);
        return value == null ? inherited : parseInt(path, value, inherited, min, max);
    }

    private int parseInt(String path, Object value, int def, int min, int max) {
        int result;
        if (value instanceof Number number) {
            double d = number.doubleValue();
            if (d != Math.floor(d) || d > Integer.MAX_VALUE || d < Integer.MIN_VALUE) {
                invalid(path, value, "liczba całkowita", def);
                return def;
            }
            result = number.intValue();
        } else {
            try {
                result = Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException e) {
                invalid(path, value, "liczba całkowita", def);
                return def;
            }
        }
        if (result < min || result > max) {
            int clamped = Math.max(min, Math.min(max, result));
            invalid(path, value, "zakres " + min + "-" + max, clamped);
            return clamped;
        }
        return result;
    }

    // ===================================================================== double

    public double getDouble(String path, double def, double min, double max) {
        Object value = value(path);
        if (value == null) {
            missing(path, def);
            return def;
        }
        return parseDouble(path, value, def, min, max);
    }

    public double optDouble(String path, double inherited, double min, double max) {
        Object value = explicit(path);
        return value == null ? inherited : parseDouble(path, value, inherited, min, max);
    }

    private double parseDouble(String path, Object value, double def, double min, double max) {
        double result;
        if (value instanceof Number number) {
            result = number.doubleValue();
        } else {
            try {
                result = Double.parseDouble(String.valueOf(value).trim().replace(',', '.'));
            } catch (NumberFormatException e) {
                invalid(path, value, "liczba", def);
                return def;
            }
        }
        if (Double.isNaN(result) || Double.isInfinite(result)) {
            invalid(path, value, "liczba", def);
            return def;
        }
        if (result < min || result > max) {
            double clamped = Math.max(min, Math.min(max, result));
            invalid(path, value, "zakres " + min + "-" + max, clamped);
            return clamped;
        }
        return result;
    }

    // ===================================================================== boolean

    public boolean getBoolean(String path, boolean def) {
        Object value = value(path);
        if (value == null) {
            missing(path, def);
            return def;
        }
        return parseBoolean(path, value, def);
    }

    public boolean optBoolean(String path, boolean inherited) {
        Object value = explicit(path);
        return value == null ? inherited : parseBoolean(path, value, inherited);
    }

    private boolean parseBoolean(String path, Object value, boolean def) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "true", "tak", "yes", "1" -> true;
            case "false", "nie", "no", "0" -> false;
            default -> {
                invalid(path, value, "true/false", def);
                yield def;
            }
        };
    }

    // ===================================================================== string

    public String getString(String path, String def) {
        Object value = value(path);
        if (value == null) {
            missing(path, def);
            return def;
        }
        return parseString(path, value, def);
    }

    public String optString(String path, String inherited) {
        Object value = explicit(path);
        return value == null ? inherited : parseString(path, value, inherited);
    }

    private String parseString(String path, Object value, String def) {
        if (value instanceof ConfigurationSection || value instanceof List<?>) {
            invalid(path, "<sekcja/lista>", "tekst", def);
            return def;
        }
        return String.valueOf(value);
    }

    // ===================================================================== list

    public List<String> getStringList(String path, List<String> def) {
        Object value = value(path);
        if (value == null) {
            missing(path, def);
            return def;
        }
        return parseList(path, value, def);
    }

    public List<String> optStringList(String path, List<String> inherited) {
        Object value = explicit(path);
        return value == null ? inherited : parseList(path, value, inherited);
    }

    private List<String> parseList(String path, Object value, List<String> def) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object o : list) {
                result.add(o == null ? "" : String.valueOf(o));
            }
            return List.copyOf(result);
        }
        if (value instanceof ConfigurationSection) {
            invalid(path, "<sekcja>", "lista tekstów", def);
            return def;
        }
        return List.of(String.valueOf(value));
    }

    // ===================================================================== material

    public Material getMaterial(String path, Material def, boolean requireItem, boolean requireBlock) {
        Object value = value(path);
        if (value == null) {
            missing(path, def);
            return def;
        }
        return parseMaterial(path, value, def, requireItem, requireBlock);
    }

    public Material optMaterial(String path, Material inherited, boolean requireItem, boolean requireBlock) {
        Object value = explicit(path);
        return value == null ? inherited : parseMaterial(path, value, inherited, requireItem, requireBlock);
    }

    private Material parseMaterial(String path, Object value, Material def, boolean requireItem, boolean requireBlock) {
        Material material = Material.matchMaterial(String.valueOf(value).trim());
        if (material == null) {
            invalid(path, value, "nazwa materiału", def);
            return def;
        }
        if (requireItem && (material.isAir() || !material.isItem())) {
            invalid(path, value, "materiał przedmiotu", def);
            return def;
        }
        if (requireBlock && (material.isAir() || !material.isBlock())) {
            invalid(path, value, "materiał bloku", def);
            return def;
        }
        return material;
    }

    // ===================================================================== color

    public Color getColor(String path, Color def) {
        Object value = value(path);
        if (value == null) {
            missing(path, def);
            return def;
        }
        return parseColor(path, value, def);
    }

    public Color optColor(String path, Color inherited) {
        Object value = explicit(path);
        return value == null ? inherited : parseColor(path, value, inherited);
    }

    private Color parseColor(String path, Object value, Color def) {
        String text = String.valueOf(value).trim();
        if (text.startsWith("#")) {
            text = text.substring(1);
        }
        try {
            if (text.length() != 6) {
                throw new NumberFormatException();
            }
            return Color.fromRGB(Integer.parseInt(text, 16));
        } catch (NumberFormatException e) {
            invalid(path, value, "kolor #RRGGBB", String.format("#%06X", def.asRGB()));
            return def;
        }
    }
}
