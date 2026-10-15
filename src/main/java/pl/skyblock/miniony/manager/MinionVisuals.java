package pl.skyblock.miniony.manager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import pl.skyblock.miniony.MinionyPlugin;
import pl.skyblock.miniony.config.HologramSettings;
import pl.skyblock.miniony.config.Appearance;
import pl.skyblock.miniony.message.Text;
import pl.skyblock.miniony.model.Minion;
import pl.skyblock.miniony.type.MinionType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Encje miniona: ciało (ArmorStand) i hologram (TextDisplay).
 * Obie encje są NIETRWAŁE (setPersistent(false)) - nie zapisują się do chunka, więc po crashu/restarcie
 * nie zostają osierocone. Dodatkowo są oznaczone kluczem PDC, co pozwala usunąć ewentualne pozostałości.
 */
public final class MinionVisuals {

    private final MinionyPlugin plugin;
    private final NamespacedKey entityKey;

    public MinionVisuals(MinionyPlugin plugin) {
        this.plugin = plugin;
        this.entityKey = new NamespacedKey(plugin, "minion_entity");
    }

    /** UUID miniona, do którego należy encja, albo null. */
    public UUID minionIdOf(Entity entity) {
        if (entity == null) {
            return null;
        }
        String value = entity.getPersistentDataContainer().get(entityKey, PersistentDataType.STRING);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isMinionEntity(Entity entity) {
        return entity != null && entity.getPersistentDataContainer().has(entityKey, PersistentDataType.STRING);
    }

    /** Czy encja jest aktualnie śledzonym ciałem/hologramem miniona. */
    public boolean isTracked(Minion minion, Entity entity) {
        if (minion == null || entity == null) {
            return false;
        }
        UUID uuid = entity.getUniqueId();
        return (minion.body() != null && minion.body().getUniqueId().equals(uuid))
                || (minion.hologram() != null && minion.hologram().getUniqueId().equals(uuid));
    }

    public Location baseLocation(Minion minion, World world) {
        return new Location(world, minion.x(), minion.y(), minion.z(), minion.yaw(), 0f);
    }

    /** Tworzy encje miniona. Wywoływać tylko, gdy chunk jest załadowany. */
    public void spawn(Minion minion, World world) {
        despawn(minion);
        MinionType type = plugin.types().get(minion.typeId());
        Appearance look = type != null ? type.level(minion).appearance() : null;
        Location base = baseLocation(minion, world);
        String tag = minion.id().toString();

        ArmorStand stand = world.spawn(base, ArmorStand.class, s -> {
            s.setPersistent(false);
            s.getPersistentDataContainer().set(entityKey, PersistentDataType.STRING, tag);
            s.setSmall(true);
            s.setArms(true);
            s.setBasePlate(false);
            s.setInvulnerable(true);
            s.setGravity(false);
            s.setSilent(true);
            s.setCanPickupItems(false);
            s.setCanMove(false);
            s.setCanTick(false);
            s.setRemoveWhenFarAway(false);
            s.setDisabledSlots(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD,
                    EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
            EntityEquipment eq = s.getEquipment();
            eq.setHelmet(plugin.items().head(look != null ? look.headTexture() : ""), true);
            if (look != null) {
                eq.setChestplate(leather(Material.LEATHER_CHESTPLATE, look), true);
                eq.setLeggings(leather(Material.LEATHER_LEGGINGS, look), true);
                eq.setBoots(leather(Material.LEATHER_BOOTS, look), true);
                eq.setItemInMainHand(new ItemStack(look.tool()), true);
            }
        });
        minion.setBody(stand);

        HologramSettings hs = plugin.settings().hologram();
        if (hs.enabled()) {
            Location holoLoc = base.clone().add(0, hs.height(), 0);
            TextDisplay display = world.spawn(holoLoc, TextDisplay.class, d -> {
                d.setPersistent(false);
                d.getPersistentDataContainer().set(entityKey, PersistentDataType.STRING, tag);
                d.setBillboard(Display.Billboard.CENTER);
                d.setDefaultBackground(false);
                d.setShadowed(true);
                d.text(hologramText(minion));
            });
            minion.setHologram(display);
        }
        minion.setHologramCache(hologramKey(minion));
    }

    private static ItemStack leather(Material material, Appearance look) {
        ItemStack item = new ItemStack(material);
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(look.armorColor());
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Usuwa encje miniona (jeśli istnieją). */
    public void despawn(Minion minion) {
        if (minion.body() != null) {
            minion.body().remove();
            minion.setBody(null);
        }
        if (minion.hologram() != null) {
            minion.hologram().remove();
            minion.setHologram(null);
        }
        minion.setHologramCache(null);
    }

    /** Czy encje miniona nadal istnieją (np. nie zostały usunięte komendą /kill). */
    public boolean isIntact(Minion minion) {
        if (minion.body() == null || !minion.body().isValid()) {
            return false;
        }
        if (plugin.settings().hologram().enabled()) {
            return minion.hologram() != null && minion.hologram().isValid();
        }
        return true;
    }

    /** Odświeża tekst hologramu tylko wtedy, gdy zmieniły się wyświetlane dane. */
    public void updateHologram(Minion minion) {
        TextDisplay display = minion.hologram();
        if (display == null || !display.isValid()) {
            return;
        }
        String key = hologramKey(minion);
        if (key.equals(minion.hologramCache())) {
            return;
        }
        minion.setHologramCache(key);
        display.text(hologramText(minion));
    }

    private static String hologramKey(Minion minion) {
        return minion.status().name() + '|' + minion.storage().usedSlots() + '|' + minion.storage().capacity()
                + '|' + minion.level() + '|' + minion.rarity() + '|' + minion.ownerName() + '|' + minion.typeId();
    }

    private Component hologramText(Minion minion) {
        TagResolver resolver = MinionPlaceholders.of(plugin, minion);
        List<Component> lines = new ArrayList<>();
        for (String line : plugin.settings().hologram().lines()) {
            lines.add(Text.parse(line, resolver));
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }
}
