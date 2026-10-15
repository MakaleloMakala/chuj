package pl.skyblock.miniony.config;

import org.bukkit.Material;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Ustawienia GUI miniona. Ostatni rząd (sloty 45-53) jest zarezerwowany na przyciski. */
public record GuiSettings(
        String title,
        Material fillerMaterial,
        String fillerName,
        List<String> itemLore,
        ButtonSettings previousPage,
        ButtonSettings collectAll,
        ButtonSettings info,
        ButtonSettings pickup,
        ButtonSettings close,
        ButtonSettings nextPage
) {

    public static final int SIZE = 54;
    public static final int STORAGE_SLOTS = 45;

    static GuiSettings read(ConfigReader reader) {
        String title = reader.getString("gui.tytul", "<dark_gray>Minion: <typ> <gray>(<strona>/<strony>)");
        Material filler = reader.getMaterial("gui.wypelnienie.material", Material.GRAY_STAINED_GLASS_PANE, true, false);
        String fillerName = reader.getString("gui.wypelnienie.nazwa", " ");
        List<String> itemLore = reader.getStringList("gui.lore-przedmiotu", List.of("", "<yellow>Kliknij, aby odebrać."));
        String base = "gui.przyciski.";
        ButtonSettings prev = ButtonSettings.read(reader, base + "poprzednia-strona", 45, Material.ARROW, "<yellow>Poprzednia strona");
        ButtonSettings collect = ButtonSettings.read(reader, base + "odbierz-wszystko", 48, Material.HOPPER, "<green>Odbierz wszystko");
        ButtonSettings info = ButtonSettings.read(reader, base + "informacje", 49, Material.BOOK, "<gold>Informacje");
        ButtonSettings pickup = ButtonSettings.read(reader, base + "zbierz-miniona", 50, Material.BARRIER, "<red>Zbierz miniona");
        ButtonSettings close = ButtonSettings.read(reader, base + "zamknij", 52, Material.OAK_DOOR, "<gray>Zamknij");
        ButtonSettings next = ButtonSettings.read(reader, base + "nastepna-strona", 53, Material.ARROW, "<yellow>Następna strona");

        ButtonSettings[] buttons = {prev, collect, info, pickup, close, next};
        int[] defaults = {45, 48, 49, 50, 52, 53};
        String[] names = {"poprzednia-strona", "odbierz-wszystko", "informacje", "zbierz-miniona", "zamknij", "nastepna-strona"};
        Set<Integer> used = new HashSet<>();
        for (int i = 0; i < buttons.length; i++) {
            ButtonSettings b = buttons[i];
            if (b.slot() < STORAGE_SLOTS || !used.add(b.slot())) {
                reader.warn("Slot przycisku '" + base + names[i] + ".slot' = " + b.slot()
                        + " musi być w ostatnim rzędzie (45-53) i nie może się powtarzać. Używam: " + defaults[i]);
                buttons[i] = new ButtonSettings(defaults[i], b.material(), b.name(), b.lore());
                used.add(defaults[i]);
            }
        }
        return new GuiSettings(title, filler, fillerName, itemLore,
                buttons[0], buttons[1], buttons[2], buttons[3], buttons[4], buttons[5]);
    }
}
