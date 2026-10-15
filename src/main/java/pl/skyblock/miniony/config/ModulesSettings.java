package pl.skyblock.miniony.config;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Ustawienia wszystkich modułów wraz z parametrami specyficznymi. */
public record ModulesSettings(
        Map<ModuleKind, ModuleSettings> modules,
        Set<Material> smeltBlacklist,
        Map<Material, Double> sellPrices,
        int sellIntervalSeconds,
        boolean sellNotify,
        List<CompactRecipe> compactRecipes,
        Map<Material, Fuel> fuels,
        int fuelMaxHours,
        int rangeBonus,
        int chestMaxDistance
) {

    /** Przepis kompaktora: {@code amount} x input -> 1 x output. */
    public record CompactRecipe(Material input, int amount, Material output) {
    }

    /** Paliwo: czas działania (sekundy) i mnożnik szybkości. */
    public record Fuel(int seconds, double speedMultiplier) {
    }

    public ModuleSettings get(ModuleKind kind) {
        return modules.get(kind);
    }

    public boolean isEnabled(ModuleKind kind) {
        ModuleSettings settings = modules.get(kind);
        return settings != null && settings.enabled();
    }

    static ModulesSettings read(ConfigReader reader) {
        Map<ModuleKind, ModuleSettings> modules = new EnumMap<>(ModuleKind.class);
        for (ModuleKind kind : ModuleKind.values()) {
            String p = "moduly." + kind.id() + ".";
            boolean enabled = reader.getBoolean(p + "wlaczony", true);
            String name = reader.getString(p + "nazwa", kind.id());
            Material icon = reader.getMaterial(p + "ikona", Material.REDSTONE, true, false);
            List<String> description = reader.getStringList(p + "opis", List.of());
            UpgradeCost cost = UpgradeCost.read(reader, p + "koszt");
            Set<String> allowed = new HashSet<>();
            for (String type : reader.getStringList(p + "dozwolone-typy", List.of())) {
                allowed.add(type.trim().toLowerCase(Locale.ROOT));
            }
            modules.put(kind, new ModuleSettings(kind, enabled, name, icon, description, cost, Set.copyOf(allowed)));
        }
        for (String key : reader.keys("moduly")) {
            if (ModuleKind.byId(key) == null) {
                reader.warn("Nieznany moduł 'moduly." + key + "' - pomijam. Dostępne: auto-przetapianie, auto-sprzedaz, "
                        + "kompaktor, paliwo, rozszerzenie-zasiegu, lacze-skrzyni.");
            }
        }

        Set<Material> blacklist = EnumSet.noneOf(Material.class);
        for (String raw : reader.getStringList("moduly.auto-przetapianie.pomijaj", List.of())) {
            Material m = Material.matchMaterial(raw.trim());
            if (m == null) {
                reader.warn("Nieznany materiał '" + raw + "' w 'moduly.auto-przetapianie.pomijaj' - pomijam.");
            } else {
                blacklist.add(m);
            }
        }

        Map<Material, Double> prices = new LinkedHashMap<>();
        for (String key : reader.keys("moduly.auto-sprzedaz.ceny")) {
            Material m = Material.matchMaterial(key.trim());
            if (m == null || !m.isItem() || m.isAir()) {
                reader.warn("Nieznany przedmiot '" + key + "' w 'moduly.auto-sprzedaz.ceny' - pomijam.");
                continue;
            }
            double price = reader.getDouble("moduly.auto-sprzedaz.ceny." + key, 0, 0, 1e12);
            if (price > 0) {
                prices.put(m, price);
            }
        }
        int sellInterval = reader.getInt("moduly.auto-sprzedaz.co-ile-sekund", 60, 5, 86_400);
        boolean sellNotify = reader.getBoolean("moduly.auto-sprzedaz.powiadomienia", false);

        List<CompactRecipe> recipes = new ArrayList<>();
        for (String raw : reader.getStringList("moduly.kompaktor.przepisy", List.of())) {
            CompactRecipe recipe = parseRecipe(raw);
            if (recipe == null) {
                reader.warn("Nieprawidłowy przepis '" + raw + "' w 'moduly.kompaktor.przepisy' (format WEJŚCIE:ilość:WYJŚCIE) - pomijam.");
            } else {
                recipes.add(recipe);
            }
        }

        Map<Material, Fuel> fuels = new LinkedHashMap<>();
        for (String key : reader.keys("moduly.paliwo.paliwa")) {
            Material m = Material.matchMaterial(key.trim());
            if (m == null || !m.isItem() || m.isAir()) {
                reader.warn("Nieznany przedmiot '" + key + "' w 'moduly.paliwo.paliwa' - pomijam.");
                continue;
            }
            String p = "moduly.paliwo.paliwa." + key + ".";
            fuels.put(m, new Fuel(reader.getInt(p + "czas-sekundy", 600, 1, 604_800),
                    reader.getDouble(p + "mnoznik-szybkosci", 1.25, 1.0, 10.0)));
        }
        int fuelMaxHours = reader.getInt("moduly.paliwo.maks-godzin", 24, 1, 8760);
        int rangeBonus = reader.getInt("moduly.rozszerzenie-zasiegu.bonus", 1, 1, 8);
        int chestDistance = reader.getInt("moduly.lacze-skrzyni.maks-odleglosc", 8, 1, 32);

        return new ModulesSettings(modules, Set.copyOf(blacklist), Map.copyOf(prices), sellInterval, sellNotify,
                List.copyOf(recipes), Map.copyOf(fuels), fuelMaxHours, rangeBonus, chestDistance);
    }

    private static CompactRecipe parseRecipe(String raw) {
        String[] parts = raw == null ? new String[0] : raw.trim().split(":");
        if (parts.length != 3) {
            return null;
        }
        Material input = Material.matchMaterial(parts[0].trim());
        Material output = Material.matchMaterial(parts[2].trim());
        int amount;
        try {
            amount = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (input == null || output == null || !input.isItem() || !output.isItem() || input.isAir() || output.isAir()
                || amount < 2 || amount > 64) {
            return null;
        }
        return new CompactRecipe(input, amount, output);
    }
}
