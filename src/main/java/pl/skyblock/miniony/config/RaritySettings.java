package pl.skyblock.miniony.config;

/**
 * Rzadkość miniona. Mnożniki działają na wartości z poziomu:
 * szybkość dzieli interwał pracy, drop i pojemność mnożą odpowiednie wartości.
 *
 * @param order       pozycja w kolejności rzadkości (0 = najniższa)
 * @param maxLevel    maksymalny poziom dla tej rzadkości (0 = bez ograniczenia, czyli maksimum typu)
 * @param limit       limit postawionych minionów tej rzadkości na gracza (-1 = brak limitu)
 * @param upgradeCost koszt ulepszenia NA tę rzadkość z poprzedniej
 */
public record RaritySettings(
        String id,
        String name,
        int order,
        double speedMultiplier,
        double dropMultiplier,
        double capacityMultiplier,
        int maxLevel,
        int moduleSlots,
        double bonusDropChance,
        int limit,
        UpgradeCost upgradeCost
) {

    public static final int MAX_MODULE_SLOTS = 9;

    public static RaritySettings fallback(String id, int order) {
        return new RaritySettings(id, "<gray>" + id, order, 1.0, 1.0, 1.0, 0, 1, 0.0, -1, UpgradeCost.FREE);
    }

    static RaritySettings read(ConfigReader reader, String key, String id, int order) {
        String p = "rzadkosci." + key + ".";
        String name = reader.getString(p + "nazwa", id);
        double speed = reader.getDouble(p + "mnoznik-szybkosci", 1.0, 0.1, 100.0);
        double drop = reader.getDouble(p + "mnoznik-dropu", 1.0, 0.0, 100.0);
        double capacity = reader.getDouble(p + "mnoznik-pojemnosci", 1.0, 0.1, 30.0);
        int maxLevel = reader.getInt(p + "max-poziom", 0, 0, TypeSettings.MAX_LEVELS);
        int slots = reader.getInt(p + "sloty-modulow", 1, 0, MAX_MODULE_SLOTS);
        double bonus = reader.getDouble(p + "szansa-bonusowego-dropu", 0.0, 0.0, 1.0);
        int limit = reader.getInt(p + "limit", -1, -1, 100_000);
        // Koszt czytany zawsze (dla najniższej rzadkości nie jest używany - nie da się na nią ulepszyć).
        UpgradeCost cost = UpgradeCost.read(reader, p + "koszt-ulepszenia");
        return new RaritySettings(id, name, order, speed, drop, capacity, maxLevel, slots, bonus, limit, cost);
    }
}
