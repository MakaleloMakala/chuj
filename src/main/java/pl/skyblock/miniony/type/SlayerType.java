package pl.skyblock.miniony.type;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.util.BoundingBox;
import pl.skyblock.miniony.config.Appearance;
import pl.skyblock.miniony.config.ConfigReader;
import pl.skyblock.miniony.model.Minion;
import pl.skyblock.miniony.model.MinionStatus;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Zabójca: zabija moby z listy w zasięgu (tylko w załadowanych chunkach i na wyspie miniona).
 * Drop trafia do magazynu (z mnożnikiem), nadmiar spada na ziemię. Pomija gracze, oswojone i (opcjonalnie) nazwane moby.
 */
public final class SlayerType extends MinionType {

    public static final String ID = "zabojca";

    private Set<EntityType> targets = EnumSet.of(EntityType.ZOMBIE);
    private int verticalRange = 2;
    private boolean skipNamed = true;
    private boolean dropExp = true;
    private int killsPerCycle = 1;

    public SlayerType() {
        super(ID, "Zabójca", new Appearance(Material.WOODEN_SWORD, Color.fromRGB(0x8B0000), ""));
    }

    @Override
    protected void loadExtra(ConfigReader reader, String base) {
        Set<EntityType> parsed = EnumSet.noneOf(EntityType.class);
        for (String raw : reader.getStringList(base + "moby", List.of("ZOMBIE", "SKELETON", "SPIDER", "CREEPER"))) {
            try {
                EntityType type = EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
                if (!type.isAlive() || type == EntityType.PLAYER || type == EntityType.ARMOR_STAND) {
                    throw new IllegalArgumentException();
                }
                parsed.add(type);
            } catch (IllegalArgumentException e) {
                reader.warn("Nieprawidłowy mob '" + raw + "' w '" + base + "moby' - pomijam.");
            }
        }
        if (parsed.isEmpty()) {
            reader.warn("Brak poprawnych mobów w '" + base + "moby'. Używam: ZOMBIE");
            parsed.add(EntityType.ZOMBIE);
        }
        targets = parsed;
        verticalRange = reader.getInt(base + "zasieg-pionowy", 2, 0, 16);
        skipNamed = reader.getBoolean(base + "pomijaj-nazwane", true);
        dropExp = reader.getBoolean(base + "upuszczaj-exp", true);
        killsPerCycle = reader.getInt(base + "zabicia-na-cykl", 1, 1, 10);
    }

    @Override
    public boolean usesBlockArea() {
        return false;
    }

    @Override
    public MinionStatus work(Minion minion, World world, WorkContext context) {
        if (minion.storage().freeSlots() == 0) {
            return MinionStatus.STORAGE_FULL;
        }
        int range = level(minion).range();
        Location center = center(minion, world);
        BoundingBox box = new BoundingBox(
                center.getX() - range - 0.5, center.getY() - verticalRange, center.getZ() - range - 0.5,
                center.getX() + range + 0.5, center.getY() + verticalRange + 1, center.getZ() + range + 0.5);
        // getNearbyEntities przeszukuje tylko załadowane encje - nie ładuje chunków
        List<LivingEntity> found = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(box, e -> e instanceof LivingEntity && targets.contains(e.getType()))) {
            LivingEntity living = (LivingEntity) entity;
            if (living instanceof Player || living.isDead() || !living.isValid() || living.isInvulnerable()) {
                continue;
            }
            if (skipNamed && living.customName() != null) {
                continue;
            }
            if (living instanceof Tameable tameable && tameable.isTamed()) {
                continue;
            }
            if (!context.islands().isInsideIsland(minion.islandId(), living.getLocation())) {
                continue;
            }
            found.add(living);
        }
        if (found.isEmpty()) {
            return MinionStatus.NO_TARGET;
        }
        found.sort((a, b) -> Double.compare(a.getLocation().distanceSquared(center), b.getLocation().distanceSquared(center)));
        int killed = 0;
        double multiplier = level(minion).dropMultiplier();
        for (LivingEntity target : found) {
            if (killed >= killsPerCycle || minion.storage().freeSlots() == 0) {
                break;
            }
            if (context.kill(minion, target, multiplier, dropExp)) {
                killed++;
            }
        }
        return killed > 0 ? MinionStatus.WORKING : MinionStatus.NO_TARGET;
    }
}
