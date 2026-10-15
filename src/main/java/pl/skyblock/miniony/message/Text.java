package pl.skyblock.miniony.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.ArrayList;
import java.util.List;

/** Pomocnicze metody MiniMessage. */
public final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Text() {
    }

    public static MiniMessage mini() {
        return MINI;
    }

    /** Parsuje tekst; przy błędnej składni zwraca tekst bez formatowania zamiast wyjątku. */
    public static Component parse(String raw, TagResolver... resolvers) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        try {
            return MINI.deserialize(raw, resolvers);
        } catch (RuntimeException e) {
            return Component.text(raw);
        }
    }

    /** Tekst na nazwę/lore przedmiotu: bez domyślnej kursywy. */
    public static Component item(String raw, TagResolver... resolvers) {
        return parse(raw, resolvers).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> itemLines(List<String> lines, TagResolver... resolvers) {
        List<Component> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(item(line, resolvers));
        }
        return result;
    }
}
