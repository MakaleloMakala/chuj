package pl.skyblock.miniony.config;

/**
 * Ustawienia jednego poziomu miniona. {@code cost} to koszt ulepszenia NA ten poziom
 * (dla poziomu 1 zawsze darmowy).
 */
public record LevelSettings(
        int level,
        int intervalSeconds,
        int capacity,
        int range,
        double dropMultiplier,
        UpgradeCost cost,
        Appearance appearance
) {
}
