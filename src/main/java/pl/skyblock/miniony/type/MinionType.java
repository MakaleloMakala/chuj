package pl.skyblock.miniony.type;

import org.bukkit.Material;
import org.bukkit.World;
import pl.skyblock.miniony.config.ConfigReader;
import pl.skyblock.miniony.config.TypeSettings;
import pl.skyblock.miniony.model.Minion;
import pl.skyblock.miniony.model.MinionStatus;

/**
 * Typ miniona. Dodanie nowego typu = jedna klasa dziedzicząca po MinionType
 * + rejestracja w {@link MinionTypeRegistry} + sekcja "typy.&lt;id&gt;" w config.yml.
 */
public abstract class MinionType {

    private final String id;
    private final String defaultName;
    private final Material defaultTool;
    private TypeSettings settings;
    private boolean configured;

    protected MinionType(String id, String defaultName, Material defaultTool) {
        this.id = id;
        this.defaultName = defaultName;
        this.defaultTool = defaultTool;
    }

    public final String id() {
        return id;
    }

    public final TypeSettings settings() {
        return settings;
    }

    /** Czy typ jest skonfigurowany i włączony. */
    public final boolean isEnabled() {
        return configured && settings != null && settings.enabled();
    }

    /** Wczytuje ustawienia wspólne i specyficzne dla typu. */
    public final void load(ConfigReader reader) {
        String base = "typy." + id + ".";
        if (reader.getSection("typy." + id) == null) {
            reader.warn("Brak sekcji 'typy." + id + "' - typ '" + id + "' jest wyłączony.");
            configured = false;
            settings = TypeSettings.defaults(id, defaultName, defaultTool);
            return;
        }
        settings = TypeSettings.read(reader, id, defaultName, defaultTool);
        loadExtra(reader, base);
        configured = true;
    }

    /** Wczytuje ustawienia specyficzne dla typu (ścieżka bazowa kończy się kropką). */
    protected abstract void loadExtra(ConfigReader reader, String base);

    /** Przesunięcie Y warstwy, na której minion pracuje (domyślnie blok pod nim). */
    public int areaYOffset() {
        return -1;
    }

    /**
     * Wykonuje jeden cykl pracy. Wywoływane wyłącznie na głównym wątku i tylko,
     * gdy chunk miniona jest załadowany. Implementacja nie może ładować chunków.
     */
    public abstract MinionStatus work(Minion minion, World world, WorkContext context);
}
