package pl.skyblock.miniony.type;

import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import pl.skyblock.miniony.hook.IslandHook;
import pl.skyblock.miniony.model.EffectiveStats;
import pl.skyblock.miniony.model.Minion;

import java.util.Collection;
import java.util.List;

/** Dane i operacje dostępne dla typu miniona podczas cyklu pracy. */
public interface WorkContext {

    IslandHook islands();

    /** Efektywne statystyki miniona (poziom x rzadkość x moduły). */
    EffectiveStats stats(Minion minion);

    /** Drop z jednego cyklu: mnożnik dropu, bonusowy drop i auto-przetapianie. */
    List<ItemStack> processDrops(Minion minion, Collection<ItemStack> drops);

    /** Czy w danym bloku stoi minion (takich bloków - i bloków pod minionami - nie wolno zmieniać). */
    boolean isMinionAt(World world, int x, int y, int z);

    /** Czy przedmiot jest przedmiotem miniona. */
    boolean isMinionItem(ItemStack item);

    /**
     * Zabija moba na rzecz miniona - drop trafia do magazynu (przetworzony jak w processDrops).
     * Zwraca true, jeśli mob zginął.
     */
    boolean kill(Minion minion, LivingEntity entity, boolean dropExp);
}
