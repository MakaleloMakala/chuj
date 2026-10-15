package pl.skyblock.miniony.config;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Bezpieczny odczyt wartości z configu. Każda brakująca lub błędna wartość
 * daje czytelny komunikat w konsoli i bezpieczną wartość domyślną zamiast wyjątku.
 */
public final class ConfigReader {

    private final ConfigurationSection root;
    private final Logger logger;
    private int warnings;

    public ConfigReader(ConfigurationSection root, Logger logger) {
        this.root = root;
        this.logger = logger;
    }

    public ConfigurationSection root() {
        return root;
    }

    public int warnings() {
        return warnings;
    }

    public void warn(String message) {
        warnings++;
        logger.warning("[Konfiguracja] " + message);
    }

    private void invalid(String path, Object value, String expected, Object fallback) {
        warn("Nieprawidłowa wartość '" + value + "' w '" + path + "' (oczekiwano: " + expected + "). Używam: " + fallback);
    }

    private void missing(String path, Object fallback) {
        warn("Brak wartości '" + path + "'. Używam: " + fallback);
    }

    public boolean has(String path) {
        return root.get(path) != null;
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

    public int getInt(String path, int def, int min, int max) {
        Object value = value(path);
        if (value == null) {
            missing(path, def);
            return def;
        }
        int result;
        if (value instanceof Number number) {
            double d = number.doubleValue();
            if (d != Math.floor(d)) {
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

    public double getDouble(String path, double def, double min, double max) {
        Object value = value(path);
        if (value == null) {
            missing(path, def);
            return def;
        }
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

    public boolean getBoolean(String path, boolean def) {
        Object value = value(path);
        if (value == null) {
            missing(path, def);
            return def;
        }
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

    public String getString(String path, String def) {
        Object value = value(path);
        if (value == null) {
            missing(path, def);
            return def;
        }
        if (value instanceof ConfigurationSection || value instanceof List<?>) {
            invalid(path, "<sekcja/lista>", "tekst", def);
            return def;
        }
        return String.valueOf(value);
    }

    public List<String> getStringList(String path, List<String> def) {
        Object value = value(path);
        if (value == null) {
            missing(path, def);
            return def;
        }
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object o : list) {
                result.add(o == null ? "" : String.valueOf(o));
            }
            return result;
        }
        if (value instanceof ConfigurationSection) {
            invalid(path, "<sekcja>", "lista tekstów", def);
            return def;
        }
        return List.of(String.valueOf(value));
    }

    public Material getMaterial(String path, Material def, boolean requireItem, boolean requireBlock) {
        Object value = value(path);
        if (value == null) {
            missing(path, def);
            return def;
        }
        Material material = Material.matchMaterial(String.valueOf(value).trim());
        if (material == null || material.isLegacy()) {
            invalid(path, value, "nazwa materiału", def);
            return def;
        }
        if (requireItem && (!material.isItem() || material.isAir())) {
            invalid(path, value, "materiał przedmiotu", def);
            return def;
        }
        if (requireBlock && (!material.isBlock() || material.isAir())) {
            invalid(path, value, "materiał bloku", def);
            return def;
        }
        return material;
    }

    public Color getColor(String path, Color def) {
        Object value = value(path);
        if (value == null) {
            missing(path, def);
            return def;
        }
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

    public ConfigurationSection getSection(String path) {
        return root.getConfigurationSection(path);
    }
}
