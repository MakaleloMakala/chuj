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
