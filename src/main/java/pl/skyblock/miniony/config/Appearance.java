package pl.skyblock.miniony.config;

import org.bukkit.Color;
import org.bukkit.Material;

/** Wygląd miniona na danym poziomie. */
public record Appearance(Material tool, Color armorColor, String headTexture) {

    static Appearance read(ConfigReader reader, String path, Appearance inherited) {
        Material tool = reader.optMaterial(path + ".narzedzie", inherited.tool(), true, false);
        Color color = reader.optColor(path + ".kolor-zbroi", inherited.armorColor());
        String texture = reader.optString(path + ".glowa-tekstura", inherited.headTexture()).trim();
        return new Appearance(tool, color, texture);
    }
}
