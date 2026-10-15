package pl.skyblock.miniony.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
        boolean upgradeOwnerOnly,
        HologramSettings hologram,
        Map<String, RaritySettings> rarities,
        List<RaritySettings> rarityOrder,
        boolean rarityUpgradeEnabled,
        boolean rarityUpgradeRequiresMaxLevel,
        OfflineSettings offline,
        ModulesSettings modules,
        GuiSettings gui,
        ModuleGuiSettings moduleGui
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
        boolean upgradeOwnerOnly = reader.getBoolean("ulepszanie.tylko-wlasciciel", true);

        Map<String, RaritySettings> rarities = new LinkedHashMap<>();
        List<RaritySettings> order = new ArrayList<>();
        for (String key : reader.keys("rzadkosci")) {
            String id = key.toUpperCase(Locale.ROOT);
            if (rarities.containsKey(id)) {
                reader.warn("Rzadkość '" + key + "' jest zdefiniowana dwa razy - pomijam duplikat.");
                continue;
            }
            RaritySettings rarity = RaritySettings.read(reader, key, id, order.size());
            rarities.put(id, rarity);
            order.add(rarity);
        }
        if (!rarities.containsKey(DEFAULT_RARITY)) {
            reader.warn("Brak rzadkości '" + DEFAULT_RARITY + "' w sekcji 'rzadkosci'. Dodaję ją jako najniższą.");
            List<RaritySettings> shifted = new ArrayList<>();
            shifted.add(RaritySettings.fallback(DEFAULT_RARITY, 0));
            for (RaritySettings r : order) {
                shifted.add(new RaritySettings(r.id(), r.name(), r.order() + 1, r.speedMultiplier(), r.dropMultiplier(),
                        r.capacityMultiplier(), r.maxLevel(), r.moduleSlots(), r.bonusDropChance(), r.limit(), r.upgradeCost()));
            }
            order = shifted;
            rarities.clear();
            for (RaritySettings r : order) {
                rarities.put(r.id(), r);
            }
        }
        boolean rarityUpgrade = reader.getBoolean("ulepszanie-rzadkosci.wlaczone", false);
        boolean rarityNeedsMax = reader.getBoolean("ulepszanie-rzadkosci.wymaga-max-poziomu", true);

        return new PluginSettings(dbFile, autosave, tickPeriod, requireIsland, memberAccess, updateBlocks,
                defLimit, maxLimit, bypassIgnoresMax, returnStorage, upgradeOwnerOnly, HologramSettings.read(reader),
                Collections.unmodifiableMap(rarities), List.copyOf(order), rarityUpgrade, rarityNeedsMax,
                OfflineSettings.read(reader), ModulesSettings.read(reader), GuiSettings.read(reader),
                ModuleGuiSettings.read(reader));
    }

    /** Rzadkość (nieznana rzadkość z bazy - zachowuje się jak domyślna, ale z własną nazwą). */
    public RaritySettings rarity(String id) {
        RaritySettings rarity = id == null ? null : rarities.get(id);
        if (rarity != null) {
            return rarity;
        }
        RaritySettings def = rarities.get(DEFAULT_RARITY);
        return new RaritySettings(id == null ? DEFAULT_RARITY : id, id == null ? def.name() : "<gray>" + id, 0,
                def.speedMultiplier(), def.dropMultiplier(), def.capacityMultiplier(), def.maxLevel(), def.moduleSlots(),
                def.bonusDropChance(), def.limit(), UpgradeCost.FREE);
    }

    /** Następna rzadkość w kolejności albo null. */
    public RaritySettings nextRarity(String id) {
        RaritySettings current = rarities.get(id);
        if (current == null || current.order() + 1 >= rarityOrder.size()) {
            return null;
        }
        return rarityOrder.get(current.order() + 1);
    }

    public String rarityName(String rarity) {
        return rarity(rarity).name();
    }
}
