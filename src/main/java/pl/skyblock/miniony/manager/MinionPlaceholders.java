package pl.skyblock.miniony.manager;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import pl.skyblock.miniony.MinionyPlugin;
import pl.skyblock.miniony.config.HologramSettings;
import pl.skyblock.miniony.config.LevelSettings;
import pl.skyblock.miniony.message.Text;
import pl.skyblock.miniony.model.Minion;
import pl.skyblock.miniony.model.MinionStatus;
import pl.skyblock.miniony.type.MinionType;

/** Zmienne MiniMessage opisujące miniona (używane w hologramie, GUI, przedmiocie i wiadomościach). */
public final class MinionPlaceholders {

    private MinionPlaceholders() {
    }

    public static String typeName(MinionyPlugin plugin, String typeId) {
        MinionType type = plugin.types().get(typeId);
        return type != null && type.settings() != null ? type.settings().displayName() : typeId;
    }

    public static String statusRaw(MinionyPlugin plugin, MinionStatus status) {
        HologramSettings h = plugin.settings().hologram();
        return switch (status) {
            case WORKING -> h.statusWorking();
            case STORAGE_FULL -> h.statusFull();
            case NO_SPACE -> h.statusNoSpace();
            case IDLE -> h.statusIdle();
            case NO_TARGET -> h.statusNoTarget();
        };
    }

    public static TagResolver of(MinionyPlugin plugin, Minion minion) {
        MinionType type = plugin.types().get(minion.typeId());
        LevelSettings level = type != null ? type.level(minion) : null;
        return TagResolver.resolver(
                Placeholder.component("typ", Text.parse(typeName(plugin, minion.typeId()))),
                Placeholder.unparsed("poziom", String.valueOf(minion.level())),
                Placeholder.component("rzadkosc", Text.parse(plugin.settings().rarityName(minion.rarity()))),
                Placeholder.unparsed("wlasciciel", minion.ownerName()),
                Placeholder.unparsed("zapelnienie", String.valueOf(minion.storage().usedSlots())),
                Placeholder.unparsed("pojemnosc", String.valueOf(minion.storage().capacity())),
                Placeholder.component("status", Text.parse(statusRaw(plugin, minion.status()))),
                Placeholder.unparsed("interwal", level == null ? "?" : String.valueOf(level.intervalSeconds())),
                Placeholder.unparsed("zasieg", level == null ? "?" : String.valueOf(level.range())),
                Placeholder.unparsed("mnoznik", level == null ? "?" : formatMultiplier(level.dropMultiplier())),
                Placeholder.unparsed("max-poziom", type == null ? "?" : String.valueOf(type.settings().maxLevel()))
        );
    }

    /** 1.0 -> "1", 1.25 -> "1.25" */
    public static String formatMultiplier(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return java.math.BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
