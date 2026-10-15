package pl.skyblock.miniony.hook;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Integracja z pluginem wysp. Gdy brak pluginu, używana jest implementacja {@link NoIslandHook}. */
public interface IslandHook {

    /** Czy integracja jest aktywna (plugin wysp jest zainstalowany). */
    boolean isEnabled();

    /** UUID wyspy w danym miejscu albo null. */
    UUID islandAt(Location location);

    /** Czy gracz jest członkiem wyspy o danym UUID. */
    boolean isMember(Player player, UUID islandId);

    /** Czy miejsce należy do wyspy o danym UUID (gdy islandId == null - zawsze true). */
    boolean isInsideIsland(UUID islandId, Location location);

    /** Zgłasza postawienie bloku przez miniona (liczniki bloków wyspy). */
    void blockPlaced(Block block);

    /** Zgłasza zniszczenie bloku przez miniona (wywoływane PRZED zmianą bloku). */
    void blockBroken(Block block);

    /** Wyrejestrowanie listenerów itp. */
    default void shutdown() {
    }
}
