package pl.skyblock.miniony.type;

import org.bukkit.configuration.ConfigurationSection;
import pl.skyblock.miniony.config.ConfigReader;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Rejestr typów minionów. */
public final class MinionTypeRegistry {

    private final Map<String, MinionType> types = new LinkedHashMap<>();

    public void register(MinionType type) {
        String key = type.id().toLowerCase(Locale.ROOT);
        if (types.containsKey(key)) {
            throw new IllegalArgumentException("Typ miniona '" + key + "' jest już zarejestrowany");
        }
        types.put(key, type);
    }

    public MinionType get(String id) {
        return id == null ? null : types.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<MinionType> all() {
        return Collections.unmodifiableCollection(types.values());
    }

    public void loadAll(ConfigReader reader) {
        for (MinionType type : types.values()) {
            type.load(reader);
        }
        ConfigurationSection section = reader.getSection("typy");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                if (!types.containsKey(key.toLowerCase(Locale.ROOT))) {
                    reader.warn("Nieznany typ miniona 'typy." + key + "' - pomijam.");
                }
            }
        }
    }
}
