package pl.skyblock.miniony;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.InventoryHolder;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.inventory.ChestInventoryMock;
import org.mockbukkit.mockbukkit.inventory.InventoryMock;

/**
 * Serwer testowy: jak ServerMock, ale inventory GUI obsługują Paperowe getHolder(false),
 * którego MockBukkit nie implementuje (na serwerze zwraca holder bez kopii stanu bloku).
 */
final class TestServer extends ServerMock {

    @Override
    public InventoryMock createInventory(InventoryHolder owner, int size, Component title) {
        return new ChestInventoryMock(owner, size) {
            @Override
            public InventoryHolder getHolder(boolean useSnapshot) {
                return getHolder();
            }
        };
    }
}
