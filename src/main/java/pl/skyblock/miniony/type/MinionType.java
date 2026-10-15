package pl.skyblock.miniony.type;

import org.bukkit.Location;
import org.bukkit.World;
import pl.skyblock.miniony.config.Appearance;
import pl.skyblock.miniony.config.ConfigReader;
import pl.skyblock.miniony.config.LevelSettings;
import pl.skyblock.miniony.config.TypeSettings;
import pl.skyblock.miniony.model.Minion;
import pl.skyblock.miniony.model.MinionStatus;

/**
 * Typ miniona. Dodanie nowego typu = jedna klasa dziedzicząca po MinionType
 * + rejestracja w {@link MinionTypeRegistry} (MinionyPlugin) + sekcja "typy.&lt;id&gt;" w config.yml.
 */
public abstract class MinionType {

    private final String id;
    private final String defaultName;
    private final Appearance defaultAppearance;
    private TypeSettings settings;
    private boolean configured;

    protected MinionType(String id, String defaultName, Appearance defaultAppearance) {
        this.id = id;
        this.defaultName = defaultName;
        this.defaultAppearance = defaultAppearance;
        this.settings = TypeSettings.defaults(id, defaultName, defaultAppearance);
    }

    public final String id() {
        return id;
    }

    public final TypeSettings settings() {
        return settings;
    }

    /** Ustawienia poziomu danego miniona. */
    public final LevelSettings level(Minion minion) {
        return settings.level(minion.level());
    }

    /** Czy typ jest skonfigurowany i włączony. */
    public final boolean isEnabled() {
        return configured && settings.enabled();
    }

    /** Wczytuje ustawienia wspólne i specyficzne dla typu. */
    public final void load(ConfigReader reader) {
        String path = "typy." + id;
        if (!reader.isSection(path)) {
            reader.warn("Brak sekcji '" + path + "' - typ '" + id + "' jest wyłączony.");
            configured = false;
            settings = TypeSettings.defaults(id, defaultName, defaultAppearance);
            return;
        }
        settings = TypeSettings.read(reader, id, defaultName, defaultAppearance);
        loadExtra(reader, path + ".");
        configured = true;
    }

    /** Wczytuje ustawienia specyficzne dla typu (ścieżka bazowa kończy się kropką). */
    protected abstract void loadExtra(ConfigReader reader, String base);

    /** Przesunięcie Y warstwy, na której minion pracuje (domyślnie blok pod nim). */
    public int areaYOffset() {
        return -1;
    }

    /** Czy typ używa obszaru bloków (false - typ pracuje na encjach i sam wyznacza obszar). */
    public boolean usesBlockArea() {
        return true;
    }

    /** Czy mnożnik dropu ma sens dla tego typu (pokazywany w GUI). */
    public boolean usesDropMultiplier() {
        return true;
    }

    /** Środek miniona (do wyszukiwania encji). */
    protected static Location center(Minion minion, World world) {
        return new Location(world, minion.x(), minion.y(), minion.z());
    }

    /**
     * Wykonuje jeden cykl pracy. Wywoływane wyłącznie na głównym wątku i tylko,
     * gdy chunk miniona jest załadowany. Implementacja nie może ładować chunków.
     */
    public abstract MinionStatus work(Minion minion, World world, WorkContext context);
}
