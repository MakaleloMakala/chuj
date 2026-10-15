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
        ButtonSettings nextPage,
        ButtonSettings upgrade,
        ButtonSettings upgradeMax,
        String costMoneyLine,
        String costItemLine,
        String costFreeLine,
        ButtonSettings modules,
        ButtonSettings rarity,
        ButtonSettings rarityMax
) {

    public static final int SIZE = 54;
    public static final int STORAGE_SLOTS = 45;

    private static int firstFree(Set<Integer> used) {
        for (int slot = STORAGE_SLOTS; slot < SIZE; slot++) {
            if (!used.contains(slot)) {
                return slot;
            }
        }
        return SIZE - 1;
    }

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
        ButtonSettings upgrade = ButtonSettings.read(reader, base + "ulepsz", 46, Material.EXPERIENCE_BOTTLE, "<green>Ulepsz do poziomu <nastepny-poziom>");
        Material maxMaterial = reader.getMaterial(base + "ulepsz-max.material", Material.NETHER_STAR, true, false);
        String maxName = reader.getString(base + "ulepsz-max.nazwa", "<gold>Maksymalny poziom");
        List<String> maxLore = reader.getStringList(base + "ulepsz-max.lore", List.of());
        String costMoney = reader.getString(base + "ulepsz.koszt-pieniadze", "<gray> - <gold><kwota>");
        String costItem = reader.getString(base + "ulepsz.koszt-przedmiot", "<gray> - <white><ilosc>x </white><nazwa>");
        String costFree = reader.getString(base + "ulepsz.koszt-darmowy", "<gray> - <green>Darmowe");

        ButtonSettings modules = ButtonSettings.read(reader, base + "moduly", 47, Material.COMPARATOR, "<aqua>Moduły");
        ButtonSettings rarity = ButtonSettings.read(reader, base + "rzadkosc", 51, Material.AMETHYST_SHARD, "<light_purple>Ulepsz rzadkość");
        Material rarityMaxMaterial = reader.getMaterial(base + "rzadkosc-max.material", Material.DRAGON_BREATH, true, false);
        String rarityMaxName = reader.getString(base + "rzadkosc-max.nazwa", "<light_purple>Najwyższa rzadkość");
        List<String> rarityMaxLore = reader.getStringList(base + "rzadkosc-max.lore", List.of());

        ButtonSettings[] buttons = {prev, collect, info, pickup, close, next, upgrade, modules, rarity};
        int[] defaults = {45, 48, 49, 50, 52, 53, 46, 47, 51};
        String[] names = {"poprzednia-strona", "odbierz-wszystko", "informacje", "zbierz-miniona", "zamknij", "nastepna-strona",
                "ulepsz", "moduly", "rzadkosc"};
        Set<Integer> used = new HashSet<>();
        for (int i = 0; i < buttons.length; i++) {
            ButtonSettings b = buttons[i];
            if (b.slot() >= STORAGE_SLOTS && used.add(b.slot())) {
                continue;
            }
            int slot = !used.contains(defaults[i]) ? defaults[i] : firstFree(used);
            reader.warn("Slot przycisku '" + base + names[i] + ".slot' = " + b.slot()
                    + " musi być w ostatnim rzędzie (45-53) i nie może się powtarzać. Używam: " + slot);
            buttons[i] = new ButtonSettings(slot, b.material(), b.name(), b.lore());
            used.add(slot);
        }
        ButtonSettings upgradeMax = new ButtonSettings(buttons[6].slot(), maxMaterial, maxName, maxLore);
        return new GuiSettings(title, filler, fillerName, itemLore,
                buttons[0], buttons[1], buttons[2], buttons[3], buttons[4], buttons[5],
                buttons[6], upgradeMax, costMoney, costItem, costFree, buttons[7], buttons[8],
                new ButtonSettings(buttons[8].slot(), rarityMaxMaterial, rarityMaxName, rarityMaxLore));
    }
}
