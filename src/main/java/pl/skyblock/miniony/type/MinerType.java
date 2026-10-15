package pl.skyblock.miniony.type;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import pl.skyblock.miniony.config.ConfigReader;
import pl.skyblock.miniony.model.Minion;
import pl.skyblock.miniony.model.MinionStatus;
import pl.skyblock.miniony.util.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Górnik: na warstwie pod sobą stawia skonfigurowany blok w pustych miejscach,
 * a gdy pustych miejsc nie ma - wykopuje jeden blok i zbiera drop do magazynu.
 * Dotyka wyłącznie powietrza i własnego bloku - inne bloki zostawia w spokoju.
 */
public final class MinerType extends MinionType {

    public static final String ID = "gornik";

    private Material block = Material.COBBLESTONE;

    public MinerType() {
        super(ID, "Górnik", Material.WOODEN_PICKAXE);
    }

    @Override
    protected void loadExtra(ConfigReader reader, String base) {
        block = reader.getMaterial(base + "blok", Material.COBBLESTONE, false, true);
        if (!block.isSolid() || block.hasGravity()) {
            reader.warn("Blok '" + block + "' w '" + base + "blok' musi być stały i nie może spadać. Używam: COBBLESTONE");
            block = Material.COBBLESTONE;
        }
    }

    @Override
    public MinionStatus work(Minion minion, World world, WorkContext context) {
        List<BlockPos> area = minion.area();
        if (area == null || area.isEmpty()) {
            return MinionStatus.NO_SPACE;
        }
        List<Block> empty = new ArrayList<>();
        List<Block> mineable = new ArrayList<>();
        for (BlockPos pos : area) {
            if (!world.isChunkLoaded(pos.chunkX(), pos.chunkZ())) {
                continue; // nigdy nie ładujemy chunków
            }
            if (context.isMinionAt(world, pos.x(), pos.y() + 1, pos.z())) {
                continue; // blok, na którym stoi inny minion
            }
            Block b = world.getBlockAt(pos.x(), pos.y(), pos.z());
            Material type = b.getType();
            if (type.isAir()) {
                empty.add(b);
            } else if (type == block) {
                mineable.add(b);
            }
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (!empty.isEmpty()) {
            Block target = empty.get(random.nextInt(empty.size()));
            target.setType(block, false);
            context.islands().blockPlaced(target);
            return MinionStatus.WORKING;
        }
        if (mineable.isEmpty()) {
            return MinionStatus.NO_SPACE;
        }
        Block target = mineable.get(random.nextInt(mineable.size()));
        Collection<ItemStack> drops = target.getDrops(new ItemStack(settings().tool()));
        if (!minion.storage().canFit(drops)) {
            return MinionStatus.STORAGE_FULL;
        }
        context.islands().blockBroken(target);
        target.setType(Material.AIR, false);
        minion.storage().add(drops);
        return MinionStatus.WORKING;
    }
}
