package pl.skyblock.miniony.type;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.inventory.ItemStack;
import pl.skyblock.miniony.config.Appearance;
import pl.skyblock.miniony.config.ConfigReader;
import pl.skyblock.miniony.model.Minion;
import pl.skyblock.miniony.model.MinionStatus;
import pl.skyblock.miniony.util.BlockPos;
import pl.skyblock.miniony.util.Drops;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Rybak: potrzebuje źródeł wody na warstwie pod sobą. Każdy cykl losuje jeden łup z tabeli w configu.
 * Nie zmienia żadnych bloków.
 */
public final class FisherType extends MinionType {

    public static final String ID = "rybak";

    private record Loot(Material material, int min, int max, int weight) {
    }

    private List<Loot> loot = List.of(new Loot(Material.COD, 1, 1, 1));
    private int totalWeight = 1;
    private int minWater = 1;

    public FisherType() {
        super(ID, "Rybak", new Appearance(Material.FISHING_ROD, Color.fromRGB(0x2E6DB4), ""));
    }

    @Override
    protected void loadExtra(ConfigReader reader, String base) {
        minWater = reader.getInt(base + "min-wody", 1, 1, 289);
        List<Loot> parsed = new ArrayList<>();
        for (String raw : reader.getStringList(base + "lup", List.of("COD:1:60", "SALMON:1:25"))) {
            Loot entry = parse(reader, base + "lup", raw);
            if (entry != null) {
                parsed.add(entry);
            }
        }
        if (parsed.isEmpty()) {
            reader.warn("Brak poprawnych wpisów w '" + base + "lup'. Używam: COD:1:1");
            parsed.add(new Loot(Material.COD, 1, 1, 1));
        }
        loot = List.copyOf(parsed);
        totalWeight = loot.stream().mapToInt(Loot::weight).sum();
    }

    /** Format: MATERIAL:ilość:waga albo MATERIAL:min-max:waga. */
    private static Loot parse(ConfigReader reader, String path, String raw) {
        String[] parts = raw == null ? new String[0] : raw.trim().split(":");
        try {
            if (parts.length != 3) {
                throw new IllegalArgumentException();
            }
            Material material = Material.matchMaterial(parts[0].trim());
            if (material == null || material.isAir() || !material.isItem()) {
                reader.warn("Nieznany przedmiot '" + parts[0] + "' w '" + path + "' - pomijam.");
                return null;
            }
            int min;
            int max;
            String amount = parts[1].trim();
            if (amount.contains("-")) {
                String[] range = amount.split("-");
                min = Integer.parseInt(range[0].trim());
                max = Integer.parseInt(range[1].trim());
            } else {
                min = max = Integer.parseInt(amount);
            }
            int weight = Integer.parseInt(parts[2].trim());
            if (min < 1 || max < min || max > 64 || weight < 1 || weight > 1_000_000) {
                throw new IllegalArgumentException();
            }
            return new Loot(material, min, max, weight);
        } catch (RuntimeException e) {
            reader.warn("Nieprawidłowy wpis łupu '" + raw + "' w '" + path + "' (format MATERIAL:ilość:waga lub MATERIAL:min-max:waga) - pomijam.");
            return null;
        }
    }

    @Override
    public MinionStatus work(Minion minion, World world, WorkContext context) {
        List<BlockPos> area = minion.area();
        if (area == null || area.isEmpty()) {
            return MinionStatus.NO_TARGET;
        }
        int water = 0;
        for (BlockPos pos : area) {
            if (!world.isChunkLoaded(pos.chunkX(), pos.chunkZ())) {
                continue;
            }
            Block b = world.getBlockAt(pos.x(), pos.y(), pos.z());
            if (b.getType() == Material.WATER && b.getBlockData() instanceof Levelled levelled && levelled.getLevel() == 0) {
                if (++water >= minWater) {
                    break;
                }
            }
        }
        if (water < minWater) {
            return MinionStatus.NO_TARGET;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int roll = random.nextInt(totalWeight);
        Loot chosen = loot.getLast();
        for (Loot entry : loot) {
            roll -= entry.weight();
            if (roll < 0) {
                chosen = entry;
                break;
            }
        }
        int amount = chosen.min() + (chosen.max() > chosen.min() ? random.nextInt(chosen.max() - chosen.min() + 1) : 0);
        List<ItemStack> drops = Drops.multiply(List.of(new ItemStack(chosen.material(), amount)), level(minion).dropMultiplier());
        if (!minion.storage().canFit(drops)) {
            return MinionStatus.STORAGE_FULL;
        }
        minion.storage().add(drops);
        return MinionStatus.WORKING;
    }
}
