package pl.skyblock.miniony.type;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import pl.skyblock.miniony.config.Appearance;
import pl.skyblock.miniony.config.ConfigReader;
import pl.skyblock.miniony.model.Minion;
import pl.skyblock.miniony.model.MinionStatus;
import pl.skyblock.miniony.util.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Wspólna logika typów "postaw blok / wykop blok" (Górnik, Drwal):
 * w pustych miejscach obszaru stawia skonfigurowany blok, a gdy pustych miejsc nie ma,
 * wykopuje jeden taki blok i zbiera drop (z mnożnikiem poziomu) do magazynu.
 * Dotyka wyłącznie powietrza i własnego bloku - inne bloki zostawia w spokoju.
 */
public abstract class BlockCycleType extends MinionType {

    private final Material defaultBlock;
    private Material block;
    private boolean warnedNoDrops;

    protected BlockCycleType(String id, String defaultName, Appearance appearance, Material defaultBlock) {
        super(id, defaultName, appearance);
        this.defaultBlock = defaultBlock;
        this.block = defaultBlock;
    }

    /** Czy pod stawianym blokiem musi być stały grunt. */
    protected abstract boolean requiresGround();

    @Override
    protected void loadExtra(ConfigReader reader, String base) {
        warnedNoDrops = false;
        block = reader.getMaterial(base + "blok", defaultBlock, false, true);
        if (!block.isSolid() || block.hasGravity()) {
            reader.warn("Blok '" + block + "' w '" + base + "blok' musi być stały i nie może spadać. Używam: " + defaultBlock);
            block = defaultBlock;
        }
    }

    public Material block() {
        return block;
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
            if (context.isMinionAt(world, pos.x(), pos.y(), pos.z())
                    || context.isMinionAt(world, pos.x(), pos.y() + 1, pos.z())) {
                continue; // blok zajęty przez miniona albo blok, na którym stoi minion
            }
            Block b = world.getBlockAt(pos.x(), pos.y(), pos.z());
            Material type = b.getType();
            if (type.isAir()) {
                if (!requiresGround() || b.getRelative(0, -1, 0).getType().isSolid()) {
                    empty.add(b);
                }
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
        ItemStack tool = new ItemStack(level(minion).appearance().tool());
        Collection<ItemStack> raw = target.getDrops(tool);
        if (raw.isEmpty()) {
            // narzędzie nie wydobywa tego bloku (np. obsydian drewnianym kilofem) - nie niszczymy go za darmo
            if (!warnedNoDrops) {
                warnedNoDrops = true;
                java.util.logging.Logger.getLogger("Miniony").warning("[Konfiguracja] Typ '" + id() + "': blok " + block
                        + " nie daje dropu narzędziem " + tool.getType() + " - minion go nie wykopuje. Zmień 'narzedzie' w poziomach.");
            }
            return MinionStatus.NO_SPACE;
        }
        Collection<ItemStack> drops = context.processDrops(minion, raw);
        if (!minion.storage().canFit(drops)) {
            return MinionStatus.STORAGE_FULL;
        }
        context.islands().blockBroken(target);
        target.setType(Material.AIR, false);
        minion.storage().add(drops);
        return MinionStatus.WORKING;
    }
}
