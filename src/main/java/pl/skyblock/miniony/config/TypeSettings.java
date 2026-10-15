package pl.skyblock.miniony.config;

import org.bukkit.Color;
import org.bukkit.Material;

import java.util.List;

/** Wspólne ustawienia typu miniona (sekcja typy.&lt;id&gt;). */
public record TypeSettings(
        String id,
        boolean enabled,
        String displayName,
        int intervalSeconds,
        int range,
        int capacity,
        Material tool,
        String headTexture,
        Color armorColor,
        Material itemMaterial,
        String itemName,
        List<String> itemLore
) {

    public static final int MAX_CAPACITY = 270;

    /** Ustawienia domyślne (typ wyłączony) - używane, gdy w configu brak sekcji typu. */
    public static TypeSettings defaults(String id, String name, Material tool) {
        return new TypeSettings(id, false, name, 15, 2, 9, tool, "", Color.fromRGB(0x7F7F7F),
                Material.PLAYER_HEAD, "<gold>Minion: <typ>", List.of());
    }

    /** Czyta wspólne ustawienia typu. Domyślne wartości przekazuje klasa typu. */
    public static TypeSettings read(ConfigReader reader, String id, String defName, Material defTool) {
        String base = "typy." + id + ".";
        boolean enabled = reader.getBoolean(base + "wlaczony", true);
        String name = reader.getString(base + "nazwa", defName);
        int interval = reader.getInt(base + "interwal-sekundy", 15, 1, 86_400);
        int range = reader.getInt(base + "zasieg", 2, 1, 8);
        int capacity = reader.getInt(base + "pojemnosc", 9, 1, MAX_CAPACITY);
        Material tool = reader.getMaterial(base + "narzedzie", defTool, true, false);
        String texture = reader.getString(base + "wyglad.glowa-tekstura", "");
        Color color = reader.getColor(base + "wyglad.kolor-zbroi", Color.fromRGB(0x7F7F7F));
        Material itemMaterial = reader.getMaterial(base + "item.material", Material.PLAYER_HEAD, true, false);
        String itemName = reader.getString(base + "item.nazwa", "<gold>Minion: <typ> <gray>[Poz. <poziom>]");
        List<String> lore = reader.getStringList(base + "item.lore", List.of("<gray>Rzadkość: <rzadkosc>"));
        return new TypeSettings(id, enabled, name, interval, range, capacity, tool, texture.trim(), color,
                itemMaterial, itemName, lore);
    }
}
