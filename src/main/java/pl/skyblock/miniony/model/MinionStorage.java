package pl.skyblock.miniony.model;

import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.Collection;

/**
 * Magazyn miniona. Tablica slotów może być większa niż pojemność (np. po zmniejszeniu pojemności w configu) -
 * wtedy nadmiarowe przedmioty nie giną, ale nowe nie są do nich dokładane.
 * Wszystkie metody muszą być wywoływane na głównym wątku.
 */
public final class MinionStorage {

    private ItemStack[] slots;
    private int capacity;

    public MinionStorage(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.slots = new ItemStack[this.capacity];
    }

    public static MinionStorage of(ItemStack[] items, int capacity) {
        MinionStorage storage = new MinionStorage(capacity);
        if (items != null) {
            int last = -1;
            for (int i = 0; i < items.length; i++) {
                if (!isEmpty(items[i])) {
                    last = i;
                }
            }
            int size = Math.max(storage.capacity, last + 1);
            storage.slots = new ItemStack[size];
            for (int i = 0; i <= last; i++) {
                storage.slots[i] = isEmpty(items[i]) ? null : items[i].clone();
            }
        }
        return storage;
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    public int capacity() {
        return capacity;
    }

    /** Liczba slotów do wyświetlenia (pojemność albo więcej, jeśli są nadmiarowe przedmioty). */
    public int size() {
        return slots.length;
    }

    public void setCapacity(int newCapacity) {
        newCapacity = Math.max(1, newCapacity);
        this.capacity = newCapacity;
        int last = -1;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null) {
                last = i;
            }
        }
        int size = Math.max(newCapacity, last + 1);
        if (size != slots.length) {
            slots = Arrays.copyOf(slots, size);
        }
    }

    public ItemStack get(int index) {
        if (index < 0 || index >= slots.length) {
            return null;
        }
        return slots[index];
    }

    public void set(int index, ItemStack item) {
        if (index < 0 || index >= slots.length) {
            return;
        }
        slots[index] = isEmpty(item) ? null : item;
        if (slots.length > capacity && index >= capacity && slots[index] == null) {
            setCapacity(capacity);
        }
    }

    public int usedSlots() {
        int used = 0;
        for (ItemStack item : slots) {
            if (item != null) {
                used++;
            }
        }
        return used;
    }

    public int totalItems() {
        int total = 0;
        for (ItemStack item : slots) {
            if (item != null) {
                total += item.getAmount();
            }
        }
        return total;
    }

    public boolean isEmpty() {
        for (ItemStack item : slots) {
            if (item != null) {
                return false;
            }
        }
        return true;
    }

    /** Sprawdza, czy wszystkie przedmioty zmieszczą się w magazynie (w granicach pojemności). */
    public boolean canFit(Collection<ItemStack> items) {
        ItemStack[] copy = new ItemStack[Math.min(capacity, slots.length)];
        for (int i = 0; i < copy.length; i++) {
            copy[i] = slots[i] == null ? null : slots[i].clone();
        }
        for (ItemStack item : items) {
            if (isEmpty(item)) {
                continue;
            }
            if (insert(copy, item.clone()) > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Dodaje przedmioty. Zwraca liczbę sztuk, które się nie zmieściły
     * (przy wcześniejszym sprawdzeniu canFit zawsze 0).
     */
    public int add(Collection<ItemStack> items) {
        ItemStack[] view = new ItemStack[Math.min(capacity, slots.length)];
        System.arraycopy(slots, 0, view, 0, view.length);
        int left = 0;
        for (ItemStack item : items) {
            if (isEmpty(item)) {
                continue;
            }
            left += insert(view, item.clone());
        }
        System.arraycopy(view, 0, slots, 0, view.length);
        return left;
    }

    private static int insert(ItemStack[] target, ItemStack item) {
        int amount = item.getAmount();
        int max = Math.max(1, item.getMaxStackSize());
        for (int i = 0; i < target.length && amount > 0; i++) {
            ItemStack existing = target[i];
            if (existing != null && existing.getAmount() < max && existing.isSimilar(item)) {
                int move = Math.min(amount, max - existing.getAmount());
                existing.setAmount(existing.getAmount() + move);
                amount -= move;
            }
        }
        for (int i = 0; i < target.length && amount > 0; i++) {
            if (target[i] == null) {
                int move = Math.min(amount, max);
                ItemStack placed = item.clone();
                placed.setAmount(move);
                target[i] = placed;
                amount -= move;
            }
        }
        return amount;
    }

    /** Liczba wolnych slotów w granicach pojemności. */
    public int freeSlots() {
        int free = 0;
        int limit = Math.min(capacity, slots.length);
        for (int i = 0; i < limit; i++) {
            if (slots[i] == null) {
                free++;
            }
        }
        return free;
    }

    /**
     * Dodaje tyle sztuk przedmiotu, ile się zmieści. Zwraca liczbę sztuk, które się nie zmieściły.
     */
    public int addPartial(ItemStack item) {
        if (isEmpty(item)) {
            return 0;
        }
        ItemStack[] view = new ItemStack[Math.min(capacity, slots.length)];
        System.arraycopy(slots, 0, view, 0, view.length);
        int left = insert(view, item.clone());
        System.arraycopy(view, 0, slots, 0, view.length);
        return left;
    }

    /** Liczba sztuk przedmiotów podobnych (isSimilar) do wzorca. */
    public int countSimilar(ItemStack template) {
        int count = 0;
        for (ItemStack item : slots) {
            if (item != null && item.isSimilar(template)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    /** Usuwa do {@code amount} sztuk przedmiotów podobnych do wzorca (od końca). Zwraca liczbę usuniętych. */
    public int removeSimilar(ItemStack template, int amount) {
        int removed = 0;
        for (int i = slots.length - 1; i >= 0 && removed < amount; i--) {
            ItemStack item = slots[i];
            if (item == null || !item.isSimilar(template)) {
                continue;
            }
            int take = Math.min(amount - removed, item.getAmount());
            removed += take;
            if (take >= item.getAmount()) {
                slots[i] = null;
            } else {
                item.setAmount(item.getAmount() - take);
            }
        }
        if (slots.length > capacity) {
            setCapacity(capacity);
        }
        return removed;
    }

    /** Kopia zawartości do zapisu. */
    public ItemStack[] snapshot() {
        ItemStack[] copy = new ItemStack[slots.length];
        for (int i = 0; i < slots.length; i++) {
            copy[i] = slots[i] == null ? null : slots[i].clone();
        }
        return copy;
    }
}
