package pl.skyblock.miniony.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Główne ustawienia pluginu wczytane i zwalidowane z config.yml. */
public record PluginSettings(
        String databaseFile,
        int autosaveSeconds,
        int workTickPeriod,
        boolean requireIsland,
        boolean memberAccess,
        boolean updateIslandBlocks,
        int defaultLimit,
        int maxLimit,
        boolean bypassIgnoresMax,
        boolean pickupReturnsStorage,
        HologramSettings hologram,
        Map<String, String> rarityNames,
        GuiSettings gui
) {

    public static final String DEFAULT_RARITY = "ZWYKLY";

    public static PluginSettings read(ConfigReader reader) {
        String dbFile = reader.getString("baza-danych.plik", "miniony.db").trim();
        if (dbFile.isEmpty() || dbFile.contains("..") || dbFile.contains("/") || dbFile.contains("\\")) {
            reader.warn("Nieprawidłowa nazwa pliku bazy '" + dbFile + "'. Używam: miniony.db");
            dbFile = "miniony.db";
        }
        int autosave = reader.getInt("baza-danych.autozapis-sekundy", 30, 5, 3600);
        int tickPeriod = reader.getInt("praca.takt-tickow", 20, 1, 1200);
        boolean requireIsland = reader.getBoolean("wyspy.wymagaj-wyspy", true);
        boolean memberAccess = reader.getBoolean("wyspy.dostep-czlonkow", true);
        boolean updateBlocks = reader.getBoolean("wyspy.aktualizuj-bloki-wyspy", true);
        int maxLimit = reader.getInt("limity.maksimum", 50, 0, 100_000);
        int defLimit = reader.getInt("limity.domyslny", 3, 0, 100_000);
        if (defLimit > maxLimit) {
            reader.warn("limity.domyslny (" + defLimit + ") jest większy niż limity.maksimum (" + maxLimit + "). Używam: " + maxLimit);
            defLimit = maxLimit;
        }
        boolean bypassIgnoresMax = reader.getBoolean("limity.bypass-ignoruje-maksimum", true);
        boolean returnStorage = reader.getBoolean("zbieranie.oddaj-magazyn", true);

        Map<String, String> rarities = new LinkedHashMap<>();
        ConfigurationSection raritySection = reader.getSection("rzadkosci");
        if (raritySection != null) {
            for (String key : raritySection.getKeys(false)) {
                String id = key.toUpperCase(Locale.ROOT);
                rarities.put(id, reader.getString("rzadkosci." + key + ".nazwa", id));
            }
        }
        if (!rarities.containsKey(DEFAULT_RARITY)) {
            reader.warn("Brak rzadkości '" + DEFAULT_RARITY + "' w sekcji 'rzadkosci'. Dodaję domyślną.");
            rarities.put(DEFAULT_RARITY, "<gray>Zwykły");
        }

        return new PluginSettings(dbFile, autosave, tickPeriod, requireIsland, memberAccess, updateBlocks,
                defLimit, maxLimit, bypassIgnoresMax, returnStorage, HologramSettings.read(reader),
                Map.copyOf(rarities), GuiSettings.read(reader));
    }

    public String rarityName(String rarity) {
        String name = rarityNames.get(rarity);
        return name != null ? name : rarity;
    }
}
