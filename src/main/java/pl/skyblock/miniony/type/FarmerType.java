package pl.skyblock.miniony.type;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.inventory.ItemStack;
import pl.skyblock.miniony.config.Appearance;
import pl.skyblock.miniony.config.ConfigReader;
import pl.skyblock.miniony.model.Minion;
import pl.skyblock.miniony.model.MinionStatus;
import pl.skyblock.miniony.util.BlockPos;
import pl.skyblock.miniony.util.Drops;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Farmer: pracuje na swoim poziomie. Kolejność w cyklu: zbiór dojrzałej uprawy (i ponowne zasadzenie),
 * zasadzenie na wolnej ziemi uprawnej (opcjonalnie zaoranie ziemi), przyspieszenie wzrostu niedojrzałej uprawy.
 */
public final class FarmerType extends MinionType {

    public static final String ID = "farmer";

    private static final Set<Material> CROPS = EnumSet.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS, Material.NETHER_WART);
    private static final Set<Material> TILLABLE = EnumSet.of(
            Material.DIRT, Material.GRASS_BLOCK, Material.DIRT_PATH);

    private Material crop = Material.WHEAT;
    private boolean till = true;
    private int growth = 1;

    public FarmerType() {
        super(ID, "Farmer", new Appearance(Material.WOODEN_HOE, Color.fromRGB(0x4CAF50), ""));
    }

    @Override
    protected void loadExtra(ConfigReader reader, String base) {
        crop = reader.getMaterial(base + "uprawa", Material.WHEAT, false, true);
        if (!CROPS.contains(crop)) {
            reader.warn("Uprawa '" + crop + "' w '" + base + "uprawa' nie jest obsługiwana (dozwolone: " + CROPS + "). Używam: WHEAT");
            crop = Material.WHEAT;
        }
        till = reader.getBoolean(base + "oraj-ziemie", true);
        growth = reader.getInt(base + "wzrost-na-cykl", 1, 1, 10);
    }

    @Override
    public int areaYOffset() {
        return 0;
    }

    private Material soil() {
        return crop == Material.NETHER_WART ? Material.SOUL_SAND : Material.FARMLAND;
    }

    @Override
    public MinionStatus work(Minion minion, World world, WorkContext context) {
        List<BlockPos> area = minion.area();
        if (area == null || area.isEmpty()) {
            return MinionStatus.NO_SPACE;
        }
        List<Block> mature = new ArrayList<>();
        List<Block> growing = new ArrayList<>();
        List<Block> plantable = new ArrayList<>();
        Material soil = soil();
        for (BlockPos pos : area) {
            if (!world.isChunkLoaded(pos.chunkX(), pos.chunkZ())
                    || context.isMinionAt(world, pos.x(), pos.y(), pos.z())) {
                continue;
            }
            Block b = world.getBlockAt(pos.x(), pos.y(), pos.z());
            Material type = b.getType();
            if (type == crop) {
                if (b.getBlockData() instanceof Ageable ageable && ageable.getAge() >= ageable.getMaximumAge()) {
                    mature.add(b);
                } else {
                    growing.add(b);
                }
            } else if (type.isAir()) {
                Material below = b.getRelative(0, -1, 0).getType();
                if (below == soil || (till && soil == Material.FARMLAND && TILLABLE.contains(below))) {
                    plantable.add(b);
                }
            }
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (!mature.isEmpty()) {
            Block target = mature.get(random.nextInt(mature.size()));
            Collection<ItemStack> drops = Drops.multiply(target.getDrops(new ItemStack(level(minion).appearance().tool())),
                    level(minion).dropMultiplier());
            if (!minion.storage().canFit(drops)) {
                return MinionStatus.STORAGE_FULL;
            }
            minion.storage().add(drops);
            // zbiór + ponowne zasadzenie: blok zostaje ten sam, wiek wraca do 0
            BlockData data = target.getBlockData();
            if (data instanceof Ageable ageable) {
                ageable.setAge(0);
                target.setBlockData(ageable, false);
            }
            return MinionStatus.WORKING;
        }
        if (!plantable.isEmpty()) {
            Block target = plantable.get(random.nextInt(plantable.size()));
            Block ground = target.getRelative(0, -1, 0);
            if (ground.getType() != soil) {
                context.islands().blockBroken(ground);
                ground.setType(Material.FARMLAND, false);
                context.islands().blockPlaced(ground);
            }
            if (ground.getBlockData() instanceof Farmland farmland) {
                farmland.setMoisture(farmland.getMaximumMoisture());
                ground.setBlockData(farmland, false);
            }
            target.setType(crop, false);
            context.islands().blockPlaced(target);
            return MinionStatus.WORKING;
        }
        if (!growing.isEmpty()) {
            Block target = growing.get(random.nextInt(growing.size()));
            if (target.getBlockData() instanceof Ageable ageable) {
                ageable.setAge(Math.min(ageable.getMaximumAge(), ageable.getAge() + growth));
                target.setBlockData(ageable, false);
            }
            return MinionStatus.WORKING;
        }
        return MinionStatus.NO_SPACE;
    }
}
