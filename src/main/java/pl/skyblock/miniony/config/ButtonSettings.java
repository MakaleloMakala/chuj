package pl.skyblock.miniony.config;

import org.bukkit.Material;

import java.util.List;

/** Ustawienia jednego przycisku GUI. */
public record ButtonSettings(int slot, Material material, String name, List<String> lore) {

    /** Wygląd bez slotu (np. ikony slotów modułów, których pozycja wynika z układu GUI). */
    static ButtonSettings readLook(ConfigReader reader, String path, Material defMaterial, String defName) {
        Material material = reader.getMaterial(path + ".material", defMaterial, true, false);
        String name = reader.getString(path + ".nazwa", defName);
        List<String> lore = reader.getStringList(path + ".lore", List.of());
        return new ButtonSettings(-1, material, name, lore);
    }

    static ButtonSettings read(ConfigReader reader, String path, int defSlot, Material defMaterial, String defName) {
        int slot = reader.getInt(path + ".slot", defSlot, 0, 53);
        Material material = reader.getMaterial(path + ".material", defMaterial, true, false);
        String name = reader.getString(path + ".nazwa", defName);
        List<String> lore = reader.getStringList(path + ".lore", List.of());
        return new ButtonSettings(slot, material, name, lore);
    }
}
