package pl.skyblock.miniony;

import org.bukkit.Location;
import org.bukkit.Material;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.world.Coordinate;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.HashMap;
import java.util.Map;

/**
 * Świat testowy: jak WorldMock, ale bloki uzupełniają metody, których MockBukkit nie implementuje
 * (isPassable), zachowaniem zgodnym z grą dla bloków używanych w testach.
 */
final class TestWorld extends WorldMock {

    private final Map<Coordinate, BlockMock> blocks = new HashMap<>();

    TestWorld() {
        super(Material.STONE, -64, 320, 60);
    }

    @Override
    public BlockMock getBlockAt(Coordinate coordinate) {
        return blocks.computeIfAbsent(coordinate, this::newBlock);
    }

    private BlockMock newBlock(Coordinate c) {
        Location location = new Location(this, c.x, c.y, c.z);
        Material material = c.y == getMinHeight() ? Material.BEDROCK : c.y <= 60 ? Material.STONE : Material.AIR;
        return new TestBlock(material, location);
    }

    /** Przedmioty na ziemi z obsługą właściciela (getOwner/setOwner nie są zaimplementowane w MockBukkit). */
    @Override
    public org.bukkit.entity.Item dropItem(Location location, org.bukkit.inventory.ItemStack item,
                                           java.util.function.Consumer<? super org.bukkit.entity.Item> function) {
        TestItem entity = new TestItem((org.mockbukkit.mockbukkit.ServerMock) org.bukkit.Bukkit.getServer(),
                java.util.UUID.randomUUID(), item);
        entity.setLocation(location);
        if (function != null) {
            function.accept(entity);
        }
        ((org.mockbukkit.mockbukkit.ServerMock) org.bukkit.Bukkit.getServer()).registerEntity(entity);
        return entity;
    }

    @Override
    public org.bukkit.entity.Item dropItem(Location location, org.bukkit.inventory.ItemStack item) {
        return dropItem(location, item, null);
    }

    static final class TestItem extends org.mockbukkit.mockbukkit.entity.ItemEntityMock {
        private java.util.UUID owner;

        TestItem(org.mockbukkit.mockbukkit.ServerMock server, java.util.UUID uuid, org.bukkit.inventory.ItemStack item) {
            super(server, uuid, item);
        }

        @Override
        public java.util.UUID getOwner() {
            return owner;
        }

        @Override
        public void setOwner(java.util.UUID owner) {
            this.owner = owner;
        }
    }

    static final class TestBlock extends BlockMock {
        TestBlock(Material material, Location location) {
            super(material, location);
        }

        @Override
        public boolean isPassable() {
            return !getType().isSolid();
        }

        /** Drop jak w grze dla bloków z testów: bruk/pień dają siebie, obsydian bez kilofa diamentowego - nic. */
        @Override
        public java.util.Collection<org.bukkit.inventory.ItemStack> getDrops(org.bukkit.inventory.ItemStack tool) {
            Material type = getType();
            if (type == Material.OBSIDIAN && (tool == null || tool.getType() != Material.DIAMOND_PICKAXE)) {
                return java.util.List.of();
            }
            return type.isItem() && !type.isAir() ? java.util.List.of(new org.bukkit.inventory.ItemStack(type)) : java.util.List.of();
        }
    }
}
