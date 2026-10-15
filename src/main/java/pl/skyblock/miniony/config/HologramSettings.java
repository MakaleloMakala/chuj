package pl.skyblock.miniony.config;

import java.util.List;

/** Ustawienia hologramu nad minionem. */
public record HologramSettings(
        boolean enabled,
        double height,
        List<String> lines,
        String statusWorking,
        String statusFull,
        String statusNoSpace,
        String statusIdle,
        String statusNoTarget
) {

    static HologramSettings read(ConfigReader reader) {
        return new HologramSettings(
                reader.getBoolean("hologram.wlaczony", true),
                reader.getDouble("hologram.wysokosc", 1.35, 0.0, 5.0),
                reader.getStringList("hologram.linie", List.of("<gold><typ> <gray>[<white>Poz. <poziom></white>]")),
                reader.getString("hologram.status.pracuje", "<green>Pracuje"),
                reader.getString("hologram.status.pelny", "<red>Magazyn pełny"),
                reader.getString("hologram.status.brak-miejsca", "<yellow>Brak miejsca do pracy"),
                reader.getString("hologram.status.oczekuje", "<gray>Oczekuje"),
                reader.getString("hologram.status.brak-celu", "<yellow>Brak celu pracy")
        );
    }
}
