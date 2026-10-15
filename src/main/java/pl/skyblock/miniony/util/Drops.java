package pl.skyblock.miniony.util;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Operacje na dropie. */
public final class Drops {

    private Drops() {
    }

    /**
     * Mnoży ilości przedmiotów. Część ułamkowa jest szansą na dodatkową sztukę
     * (np. 3 sztuki x 1.5 = 4 sztuki + 50% szans na piątą). Wynik jest podzielony na pełne stosy.
     */
    public static List<ItemStack> multiply(Collection<ItemStack> drops, double multiplier) {
        List<ItemStack> result = new ArrayList<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (ItemStack drop : drops) {
            if (drop == null || drop.getType().isAir() || drop.getAmount() <= 0) {
                continue;
            }
            double exact = drop.getAmount() * multiplier;
            long whole = (long) Math.floor(exact);
            if (random.nextDouble() < exact - whole) {
                whole++;
            }
            addSplit(result, drop, (int) Math.min(whole, 100_000));
        }
        return result;
    }

    /** Suma sztuk jednego rodzaju przedmiotu. */
    public record Total(ItemStack template, long amount) {
    }

    /** Sumuje sztuki podobnych przedmiotów (isSimilar). Wzorce mają ilość 1. */
    public static List<Total> merge(Collection<ItemStack> items) {
        List<ItemStack> templates = new ArrayList<>();
        List<long[]> amounts = new ArrayList<>();
        outer:
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
                continue;
            }
            for (int i = 0; i < templates.size(); i++) {
                if (templates.get(i).isSimilar(item)) {
                    amounts.get(i)[0] += item.getAmount();
                    continue outer;
                }
            }
            ItemStack template = item.clone();
            template.setAmount(1);
            templates.add(template);
            amounts.add(new long[]{item.getAmount()});
        }
        List<Total> result = new ArrayList<>(templates.size());
        for (int i = 0; i < templates.size(); i++) {
            result.add(new Total(templates.get(i), amounts.get(i)[0]));
        }
        return result;
    }

    /** Dodaje {@code amount} sztuk przedmiotu podzielonych na stosy o maksymalnym rozmiarze. */
    public static void addSplit(List<ItemStack> target, ItemStack template, int amount) {
        int max = Math.max(1, template.getMaxStackSize());
        while (amount > 0) {
            int part = Math.min(max, amount);
            ItemStack stack = template.clone();
            stack.setAmount(part);
            target.add(stack);
            amount -= part;
        }
    }
}
