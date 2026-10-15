package pl.skyblock.miniony.type;

import org.bukkit.World;
import pl.skyblock.miniony.hook.IslandHook;

/** Dane dostępne dla typu miniona podczas cyklu pracy. */
public interface WorkContext {

    IslandHook islands();

    /** Czy w danym bloku stoi minion (takich bloków - i bloków pod minionami - nie wolno zmieniać). */
    boolean isMinionAt(World world, int x, int y, int z);
}
