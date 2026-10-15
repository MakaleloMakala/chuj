package pl.skyblock.miniony.type;

import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import pl.skyblock.miniony.hook.IslandHook;
import pl.skyblock.miniony.model.Minion;

/** Dane i operacje dostępne dla typu miniona podczas cyklu pracy. */
public interface WorkContext {

    IslandHook islands();

    /** Czy w danym bloku stoi minion (takich bloków - i bloków pod minionami - nie wolno zmieniać). */
    boolean isMinionAt(World world, int x, int y, int z);

    /** Czy przedmiot jest przedmiotem miniona. */
    boolean isMinionItem(ItemStack item);

    /**
     * Zabija moba na rzecz miniona - drop trafia do magazynu miniona (z mnożnikiem dropu).
     * Zwraca true, jeśli mob zginął.
     */
    boolean kill(Minion minion, LivingEntity entity, double dropMultiplier, boolean dropExp);
}
