package pl.skyblock.miniony.type;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import pl.skyblock.miniony.config.Appearance;
import pl.skyblock.miniony.config.ConfigReader;
import pl.skyblock.miniony.config.LevelSettings;
import pl.skyblock.miniony.config.OfflineDrops;
import pl.skyblock.miniony.config.TypeSettings;
import pl.skyblock.miniony.model.Minion;
import pl.skyblock.miniony.model.MinionStatus;
import pl.skyblock.miniony.util.Drops;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /** Czy typ może produkować w trybie offline (domyślnie wg sekcji "offline" w configu). */
    public boolean supportsOffline() {
        return settings.offline().enabled();
    }

    /**
     * Surowy drop z {@code cycles} cykli pracy offline (przed mnożnikami i modułami).
     * Domyślnie: co "cykle-na-drop" cykli jedna jednostka dropu z configu.
     */
    public List<ItemStack> simulateOffline(Minion minion, long cycles) {
        OfflineDrops offline = settings.offline();
        List<ItemStack> result = new ArrayList<>();
        if (!offline.enabled() || offline.drops().isEmpty() || cycles <= 0) {
            return result;
        }
        long units = cycles / offline.cyclesPerDrop();
        for (Map.Entry<Material, Integer> entry : offline.drops().entrySet()) {
            Drops.addSplit(result, new ItemStack(entry.getKey()), (int) Math.min(units * entry.getValue(), MAX_OFFLINE_ITEMS));
        }
        return result;
    }

    /** Limit sztuk jednego przedmiotu z jednej symulacji offline (ochrona pamięci). */
    protected static final long MAX_OFFLINE_ITEMS = 200_000L;

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
