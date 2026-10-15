package pl.skyblock.miniony.model;

/**
 * Efektywne statystyki miniona: poziom x rzadkość x moduły (x paliwo).
 *
 * @param intervalMs         interwał pracy z uwzględnieniem paliwa
 * @param baseIntervalMs     interwał pracy bez paliwa
 * @param fuelMultiplier     mnożnik szybkości z aktywnego paliwa (1 = brak)
 * @param dropMultiplier     mnożnik dropu (poziom x rzadkość)
 * @param bonusDropChance    szansa na podwójny drop w cyklu
 * @param maxLevel           maksymalny poziom (typ, ograniczony przez rzadkość)
 * @param moduleSlots        liczba slotów modułów (z rzadkości)
 */
public record EffectiveStats(
        long intervalMs,
        long baseIntervalMs,
        double fuelMultiplier,
        int capacity,
        int range,
        double dropMultiplier,
        double bonusDropChance,
        int maxLevel,
        int moduleSlots
) {
}
