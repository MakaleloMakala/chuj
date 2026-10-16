package pl.skyblock.miniony.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Pomocnicze metody GUI. */
public final class Guis {

    private Guis() {
    }

    /**
     * Holder inventory bez tworzenia kopii stanu bloku (getHolder(false)) - ważne, bo listenery GUI
     * sprawdzają każde kliknięcie na serwerze.
     */
    public static InventoryHolder holder(Inventory inventory) {
        return inventory == null ? null : inventory.getHolder(false);
    }

    /** Holder górnego inventory aktualnie otwartego widoku gracza. */
    public static InventoryHolder openHolder(org.bukkit.entity.HumanEntity player) {
        org.bukkit.inventory.InventoryView view = player.getOpenInventory();
        return view == null ? null : holder(view.getTopInventory());
    }
}
