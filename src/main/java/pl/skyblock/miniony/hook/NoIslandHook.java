package pl.skyblock.miniony.hook;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Brak pluginu wysp - sprawdzanie wysp wyłączone. */
public final class NoIslandHook implements IslandHook {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public UUID islandAt(Location location) {
        return null;
    }

    @Override
    public boolean isMember(Player player, UUID islandId) {
        return false;
    }

    @Override
    public boolean isInsideIsland(UUID islandId, Location location) {
        return true;
    }

    @Override
    public void blockPlaced(Block block) {
    }

    @Override
    public void blockBroken(Block block) {
    }
}
