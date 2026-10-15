package pl.skyblock.miniony.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Koszt ulepszenia na dany poziom. Gdy w configu jest błąd w koszcie, {@code valid == false}
 * i ulepszenie jest zablokowane (bezpieczniej niż pominąć błędny wpis i obniżyć koszt).
 */
public record UpgradeCost(double money, List<CostItem> items, boolean valid) {

    public static final UpgradeCost FREE = new UpgradeCost(0, List.of(), true);

    public boolean isFree() {
        return money <= 0 && items.isEmpty();
    }

    static UpgradeCost read(ConfigReader reader, String path) {
        double money = reader.optDouble(path + ".pieniadze", 0, 0, 1e15);
        List<String> rawItems = reader.optStringList(path + ".przedmioty", List.of());
        List<CostItem> items = new ArrayList<>();
        boolean valid = true;
        for (String raw : rawItems) {
            CostItem item = CostItem.parse(reader, path + ".przedmioty", raw);
            if (item == null) {
                valid = false;
            } else {
                items.add(item);
            }
        }
        if (!valid) {
            reader.warn("Koszt '" + path + "' zawiera błędy - ulepszenie na ten poziom jest zablokowane do czasu poprawy.");
        }
        return new UpgradeCost(money, List.copyOf(items), valid);
    }
}
