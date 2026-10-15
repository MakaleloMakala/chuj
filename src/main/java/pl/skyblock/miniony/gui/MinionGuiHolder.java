package pl.skyblock.miniony.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/** Holder GUI miniona - identyfikuje inventory jako nasze i przechowuje miniona oraz stronę. */
public final class MinionGuiHolder implements InventoryHolder {

    private final UUID minionId;
    private final UUID viewer;
    private int page;
    private Inventory inventory;

    MinionGuiHolder(UUID minionId, UUID viewer, int page) {
        this.minionId = minionId;
        this.viewer = viewer;
        this.page = page;
    }

    public UUID minionId() {
        return minionId;
    }

    public UUID viewer() {
        return viewer;
    }

    public int page() {
        return page;
    }

    void setPage(int page) {
        this.page = page;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
