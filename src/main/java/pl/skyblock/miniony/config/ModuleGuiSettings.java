package pl.skyblock.miniony.config;

import org.bukkit.Material;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * GUI modułów (36 slotów): rząd 1 - sloty modułów miniona, rząd 3 - moduły do kupienia,
 * rząd 4 (27-35) - przyciski.
 */
public record ModuleGuiSettings(
        String title,
        ButtonSettings freeSlot,
        ButtonSettings lockedSlot,
        List<String> installedLore,
        List<String> inactiveLore,
        List<String> availableLore,
        List<String> alreadyInstalledLore,
        List<String> notAllowedLore,
        ButtonSettings back,
        ButtonSettings fuel,
        ButtonSettings chestLink,
        ButtonSettings close
) {

    public static final int SIZE = 36;
    public static final int AVAILABLE_ROW_START = 18;
    public static final int BUTTON_ROW_START = 27;

    static ModuleGuiSettings read(ConfigReader reader) {
        String b = "gui-moduly.";
        String title = reader.getString(b + "tytul", "<dark_gray>Moduły: <typ>");
        ButtonSettings free = ButtonSettings.readLook(reader, b + "wolny-slot", Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>Wolny slot");
        ButtonSettings locked = ButtonSettings.readLook(reader, b + "zablokowany-slot", Material.BLACK_STAINED_GLASS_PANE, "<dark_gray>Zablokowany slot");
        List<String> installed = reader.getStringList(b + "lore-zainstalowany", List.of("", "<red>Shift + klik: odinstaluj"));
        List<String> inactive = reader.getStringList(b + "lore-nieaktywny", List.of("", "<red>Nieaktywny"));
        List<String> available = reader.getStringList(b + "lore-do-kupienia", List.of("", "<gray>Koszt:", "<koszt>", "", "<yellow>Kliknij, aby zainstalować."));
        List<String> already = reader.getStringList(b + "lore-juz-zainstalowany", List.of("", "<green>Zainstalowany"));
        List<String> notAllowed = reader.getStringList(b + "lore-niedozwolony", List.of("", "<red>Niedostępny dla tego typu"));
        ButtonSettings back = ButtonSettings.read(reader, b + "przyciski.powrot", 27, Material.ARROW, "<yellow>Powrót");
        ButtonSettings fuel = ButtonSettings.read(reader, b + "przyciski.paliwo", 30, Material.COAL, "<gold>Dolej paliwa");
        ButtonSettings chest = ButtonSettings.read(reader, b + "przyciski.lacze-skrzyni", 32, Material.CHEST, "<gold>Ustaw skrzynię");
        ButtonSettings close = ButtonSettings.read(reader, b + "przyciski.zamknij", 35, Material.OAK_DOOR, "<gray>Zamknij");

        ButtonSettings[] buttons = {back, fuel, chest, close};
        int[] defaults = {27, 30, 32, 35};
        String[] names = {"powrot", "paliwo", "lacze-skrzyni", "zamknij"};
        Set<Integer> used = new HashSet<>();
        for (int i = 0; i < buttons.length; i++) {
            ButtonSettings button = buttons[i];
            if (button.slot() >= BUTTON_ROW_START && button.slot() < SIZE && used.add(button.slot())) {
                continue;
            }
            int slot = defaults[i];
            if (used.contains(slot)) {
                for (int s = BUTTON_ROW_START; s < SIZE; s++) {
                    if (!used.contains(s)) {
                        slot = s;
                        break;
                    }
                }
            }
            reader.warn("Slot przycisku '" + b + "przyciski." + names[i] + ".slot' = " + button.slot()
                    + " musi być w ostatnim rzędzie (27-35) i nie może się powtarzać. Używam: " + slot);
            buttons[i] = new ButtonSettings(slot, button.material(), button.name(), button.lore());
            used.add(slot);
        }
        return new ModuleGuiSettings(title, free, locked, installed, inactive, available, already, notAllowed,
                buttons[0], buttons[1], buttons[2], buttons[3]);
    }
}
