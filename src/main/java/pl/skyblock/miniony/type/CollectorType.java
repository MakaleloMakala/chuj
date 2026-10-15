package pl.skyblock.miniony.type;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import pl.skyblock.miniony.config.Appearance;
import pl.skyblock.miniony.config.ConfigReader;
import pl.skyblock.miniony.model.Minion;
import pl.skyblock.miniony.model.MinionStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Zbieracz: podnosi leżące przedmioty w zasięgu do magazynu. Nie stosuje mnożnika dropu
 * (zbiera istniejące przedmioty - mnożenie tworzyłoby przedmioty z niczego).
 * Pomija przedmioty z opóźnieniem podnoszenia (np. przedmioty wystawowe), z właścicielem i przedmioty minionów.
 */
public final class CollectorType extends MinionType {

    public static final String ID = "zbieracz";

    private int verticalRange = 2;
    private int stacksPerCycle = 4;

    public CollectorType() {
        super(ID, "Zbieracz", new Appearance(Material.HOPPER, Color.fromRGB(0x9E9E9E), ""));
    }

    @Override
    protected void loadExtra(ConfigReader reader, String base) {
        verticalRange = reader.getInt(base + "zasieg-pionowy", 2, 0, 16);
        stacksPerCycle = reader.getInt(base + "stosy-na-cykl", 4, 1, 64);
    }

    @Override
    public boolean usesBlockArea() {
        return false;
    }

    @Override
    public boolean usesDropMultiplier() {
        return false;
    }

    @Override
    public MinionStatus work(Minion minion, World world, WorkContext context) {
        int range = level(minion).range();
        Location center = center(minion, world);
        BoundingBox box = new BoundingBox(
                center.getX() - range - 0.5, center.getY() - verticalRange, center.getZ() - range - 0.5,
                center.getX() + range + 0.5, center.getY() + verticalRange + 1, center.getZ() + range + 0.5);
        List<Item> items = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(box, e -> e instanceof Item)) {
            Item item = (Item) entity;
            if (!item.isValid() || item.isDead() || item.getPickupDelay() > 0 || item.getOwner() != null) {
                continue;
            }
            ItemStack stack = item.getItemStack();
            if (stack.getType().isAir() || context.isMinionItem(stack)) {
                continue;
            }
            if (!context.islands().isInsideIsland(minion.islandId(), item.getLocation())) {
                continue;
            }
            items.add(item);
        }
        if (items.isEmpty()) {
            return MinionStatus.NO_TARGET;
        }
        int collected = 0;
        boolean full = false;
        for (Item item : items) {
            if (collected >= stacksPerCycle) {
                break;
            }
            ItemStack stack = item.getItemStack();
            int left = minion.storage().addPartial(stack);
            if (left >= stack.getAmount()) {
                full = true;
                break;
            }
            if (left == 0) {
                item.remove();
            } else {
                ItemStack rest = stack.clone();
                rest.setAmount(left);
                item.setItemStack(rest);
                full = true;
            }
            collected++;
            if (full) {
                break;
            }
        }
        if (collected > 0) {
            return MinionStatus.WORKING;
        }
        return full ? MinionStatus.STORAGE_FULL : MinionStatus.NO_TARGET;
    }
}
