package pl.skyblock.miniony.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Wiadomości z sekcji "wiadomosci" w config.yml. Brakujące klucze biorą wartość
 * z domyślnego configu w pliku jar (ustawionego jako defaults) - a gdy i tam ich nie ma,
 * w konsoli pojawia się ostrzeżenie.
 */
public final class Messages {

    private final Logger logger;
    private final Map<String, Object> values = new HashMap<>();
    private final Set<String> reportedMissing = new HashSet<>();

    public Messages(Logger logger) {
        this.logger = logger;
    }

    public void load(ConfigurationSection root) {
        values.clear();
        reportedMissing.clear();
        ConfigurationSection section = root.getConfigurationSection("wiadomosci");
        if (section == null) {
            logger.warning("[Konfiguracja] Brak sekcji 'wiadomosci'. Używam wartości domyślnych.");
            return;
        }
        for (String key : section.getKeys(false)) {
            values.put(key, section.get(key));
        }
        ConfigurationSection defaults = section.getDefaultSection();
        if (defaults != null) {
            for (String key : defaults.getKeys(false)) {
                if (!values.containsKey(key)) {
                    logger.warning("[Konfiguracja] Brak wiadomości 'wiadomosci." + key + "'. Używam wartości domyślnej.");
                    values.put(key, defaults.get(key));
                }
            }
        }
    }

    public String raw(String key) {
        Object value = values.get(key);
        if (value == null) {
            if (reportedMissing.add(key)) {
                logger.warning("[Konfiguracja] Brak wiadomości 'wiadomosci." + key + "'.");
            }
            return key;
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object o : list) {
                if (!sb.isEmpty()) {
                    sb.append("<newline>");
                }
                sb.append(o);
            }
            return sb.toString();
        }
        return String.valueOf(value);
    }

    public List<String> lines(String key) {
        Object value = values.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (value == null) {
            if (reportedMissing.add(key)) {
                logger.warning("[Konfiguracja] Brak wiadomości 'wiadomosci." + key + "'.");
            }
            return List.of();
        }
        return List.of(String.valueOf(value));
    }

    public Component component(String key, TagResolver... resolvers) {
        return Text.parse(raw(key), resolvers);
    }

    /** Wysyła wiadomość. Pusta wartość w configu wyłącza daną wiadomość. */
    public void send(CommandSender target, String key, TagResolver... resolvers) {
        if (target == null) {
            return;
        }
        String raw = raw(key);
        if (raw.isEmpty()) {
            return;
        }
        target.sendMessage(Text.parse(raw, resolvers));
    }

    public void sendLines(CommandSender target, String key, TagResolver... resolvers) {
        for (String line : lines(key)) {
            target.sendMessage(Text.parse(line, resolvers));
        }
    }
}
