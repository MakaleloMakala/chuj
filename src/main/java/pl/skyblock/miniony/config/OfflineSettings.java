package pl.skyblock.miniony.config;

/** Praca offline (symulowana produkcja, gdy chunk miniona był wyładowany). */
public record OfflineSettings(boolean enabled, int maxHours, boolean countServerDowntime) {

    static OfflineSettings read(ConfigReader reader) {
        return new OfflineSettings(
                reader.getBoolean("praca-offline.wlaczona", true),
                reader.getInt("praca-offline.maks-godzin", 12, 0, 720),
                reader.getBoolean("praca-offline.licz-czas-wylaczenia-serwera", false));
    }
}
