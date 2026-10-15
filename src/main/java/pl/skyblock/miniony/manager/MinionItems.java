package pl.skyblock.miniony.manager;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import pl.skyblock.miniony.MinionyPlugin;
import pl.skyblock.miniony.config.TypeSettings;
import pl.skyblock.miniony.message.Text;
import pl.skyblock.miniony.model.Minion;
import pl.skyblock.miniony.type.MinionType;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Przedmioty minionów. W PersistentDataContainer zapisane są: id miniona, token (ochrona przed duplikacją),
 * typ, poziom i rzadkość. Autorytatywne dane są w bazie - przedmiot jest tylko "kluczem".
 */
public final class MinionItems {

    private final MinionyPlugin plugin;
    private final NamespacedKey idKey;
    private final NamespacedKey tokenKey;
    private final NamespacedKey typeKey;
    private final NamespacedKey levelKey;
    private final NamespacedKey rarityKey;

    public MinionItems(MinionyPlugin plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "minion_id");
        this.tokenKey = new NamespacedKey(plugin, "minion_token");
        this.typeKey = new NamespacedKey(plugin, "minion_type");
        this.levelKey = new NamespacedKey(plugin, "minion_level");
        this.rarityKey = new NamespacedKey(plugin, "minion_rarity");
    }

    public ItemStack create(Minion minion) {
        MinionType type = plugin.types().get(minion.typeId());
        TypeSettings ts = type != null ? type.settings() : null;
        Material material = ts != null ? ts.itemMaterial() : Material.PLAYER_HEAD;
        ItemStack item = material == Material.PLAYER_HEAD && ts != null
                ? head(ts.headTexture())
                : new ItemStack(material);
        TagResolver resolver = MinionPlaceholders.of(plugin, minion);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.item(ts != null ? ts.itemName() : "<gold>Minion: <typ>", resolver));
            if (ts != null) {
                meta.lore(Text.itemLines(ts.itemLore(), resolver));
            }
            meta.setMaxStackSize(1);
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(idKey, PersistentDataType.STRING, minion.id().toString());
            pdc.set(tokenKey, PersistentDataType.STRING, minion.token().toString());
            pdc.set(typeKey, PersistentDataType.STRING, minion.typeId());
            pdc.set(levelKey, PersistentDataType.INTEGER, minion.level());
            pdc.set(rarityKey, PersistentDataType.STRING, minion.rarity());
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Głowa z teksturą (base64). Pusta tekstura = zwykła głowa gracza. */
    public ItemStack head(String texture) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (texture == null || texture.isBlank()) {
            return head;
        }
        try {
            ItemMeta meta = head.getItemMeta();
            if (meta instanceof SkullMeta skull) {
                PlayerProfile profile = Bukkit.createProfile(
                        UUID.nameUUIDFromBytes(texture.getBytes(StandardCharsets.UTF_8)), "minion");
                profile.setProperty(new ProfileProperty("textures", texture));
                skull.setPlayerProfile(profile);
                head.setItemMeta(skull);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("[Konfiguracja] Nieprawidłowa tekstura głowy miniona: " + e.getMessage());
        }
        return head;
    }

    public boolean isMinionItem(ItemStack item) {
        return item != null && !item.getType().isAir()
                && item.getPersistentDataContainer().has(idKey, PersistentDataType.STRING);
    }

    public UUID readId(ItemStack item) {
        return readUuid(item, idKey);
    }

    public UUID readToken(ItemStack item) {
        return readUuid(item, tokenKey);
    }

    private static UUID readUuid(ItemStack item, NamespacedKey key) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        String value = item.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
