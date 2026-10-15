package pl.skyblock.miniony.command;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pl.skyblock.miniony.MinionyPlugin;
import pl.skyblock.miniony.manager.MinionPlaceholders;
import pl.skyblock.miniony.message.Messages;
import pl.skyblock.miniony.model.Minion;
import pl.skyblock.miniony.type.MinionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Komenda /minion. */
public final class MinionCommand implements TabExecutor {

    private static final String PERM_USE = "miniony.use";
    private static final String PERM_GIVE = "miniony.admin.give";
    private static final String PERM_RELOAD = "miniony.admin.reload";
    private static final String PERM_LIST_OTHERS = "miniony.admin.list";
    private static final String PERM_LIMIT_OTHERS = "miniony.admin.limit";
    private static final int MAX_GIVE = 64;

    private final MinionyPlugin plugin;

    public MinionCommand(MinionyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "pomoc" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "pomoc", "help" -> help(sender);
            case "give", "daj" -> give(sender, args);
            case "list", "lista" -> list(sender, args);
            case "limit" -> limit(sender, args);
            case "reload", "przeladuj" -> reload(sender);
            case "odbierz" -> collectPending(sender);
            default -> plugin.messages().send(sender, "nieznana-komenda");
        }
        return true;
    }

    private void help(CommandSender sender) {
        Messages msg = plugin.messages();
        msg.sendLines(sender, "pomoc");
        if (sender.hasPermission(PERM_GIVE) || sender.hasPermission(PERM_RELOAD)
                || sender.hasPermission(PERM_LIST_OTHERS) || sender.hasPermission(PERM_LIMIT_OTHERS)) {
            msg.sendLines(sender, "pomoc-admin");
        }
    }

    private void give(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        if (!sender.hasPermission(PERM_GIVE)) {
            msg.send(sender, "brak-uprawnien");
            return;
        }
        if (args.length < 3) {
            msg.send(sender, "uzycie-give");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            msg.send(sender, "gracz-offline", Placeholder.unparsed("gracz", args[1]));
            return;
        }
        MinionType type = plugin.types().get(args[2]);
        if (type == null) {
            msg.send(sender, "nieznany-typ", Placeholder.unparsed("typ", args[2]),
                    Placeholder.unparsed("typy", String.join(", ", typeIds())));
            return;
        }
        if (!type.isEnabled()) {
            msg.send(sender, "typ-wylaczony");
            return;
        }
        int amount = 1;
        int level = 1;
        String rarity = pl.skyblock.miniony.config.PluginSettings.DEFAULT_RARITY;
        if (args.length >= 6) {
            rarity = args[5].toUpperCase(Locale.ROOT);
            if (!plugin.settings().rarities().containsKey(rarity)) {
                msg.send(sender, "nieznana-rzadkosc", Placeholder.unparsed("rzadkosc", args[5]),
                        Placeholder.unparsed("rzadkosci", String.join(", ", plugin.settings().rarities().keySet())));
                return;
            }
        }
        if (args.length >= 5) {
            int max = type.settings().maxLevel();
            try {
                level = Integer.parseInt(args[4]);
            } catch (NumberFormatException e) {
                level = -1;
            }
            if (level < 1 || level > max) {
                msg.send(sender, "nieprawidlowy-poziom", Placeholder.unparsed("wartosc", args[4]),
                        Placeholder.unparsed("max", String.valueOf(max)));
                return;
            }
        }
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                amount = -1;
            }
            if (amount < 1 || amount > MAX_GIVE) {
                msg.send(sender, "nieprawidlowa-liczba", Placeholder.unparsed("wartosc", args[3]));
                return;
            }
        }
        for (int i = 0; i < amount; i++) {
            Minion minion = plugin.minions().createItemMinion(type, target, level, rarity);
            ItemStack item = plugin.items().create(minion);
            Map<Integer, ItemStack> rest = target.getInventory().addItem(item);
            for (ItemStack r : rest.values()) {
                // Przedmiot jest chroniony tokenem - wyrzucenie na ziemię nie pozwala na duplikację.
                target.getWorld().dropItem(target.getLocation(), r, drop -> drop.setOwner(target.getUniqueId()));
            }
        }
        var typeName = Placeholder.component("typ", pl.skyblock.miniony.message.Text.parse(type.settings().displayName()));
        msg.send(sender, "dano-miniona", typeName, Placeholder.unparsed("ilosc", String.valueOf(amount)),
                Placeholder.unparsed("gracz", target.getName()));
        if (sender != target) {
            msg.send(target, "otrzymano-miniona", typeName, Placeholder.unparsed("ilosc", String.valueOf(amount)));
        }
    }

    private void list(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        UUID targetId;
        String targetName;
        if (args.length >= 2) {
            if (!sender.hasPermission(PERM_LIST_OTHERS)) {
                msg.send(sender, "brak-uprawnien");
                return;
            }
            Player online = Bukkit.getPlayerExact(args[1]);
            if (online != null) {
                targetId = online.getUniqueId();
                targetName = online.getName();
            } else {
                OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (offline == null) {
                    msg.send(sender, "nieznany-gracz", Placeholder.unparsed("gracz", args[1]));
                    return;
                }
                targetId = offline.getUniqueId();
                targetName = offline.getName() == null ? args[1] : offline.getName();
            }
        } else {
            if (!(sender instanceof Player player)) {
                msg.send(sender, "tylko-gracz");
                return;
            }
            if (!player.hasPermission(PERM_USE)) {
                msg.send(sender, "brak-uprawnien");
                return;
            }
            targetId = player.getUniqueId();
            targetName = player.getName();
        }
        List<Minion> placed = plugin.minions().placedBy(targetId);
        int pending = plugin.minions().pendingCount(targetId);
        if (placed.isEmpty()) {
            msg.send(sender, "lista-pusta", Placeholder.unparsed("gracz", targetName));
        } else {
            msg.send(sender, "lista-naglowek", Placeholder.unparsed("gracz", targetName),
                    Placeholder.unparsed("liczba", String.valueOf(placed.size())));
            for (Minion m : placed) {
                msg.send(sender, "lista-pozycja", MinionPlaceholders.of(plugin, m),
                        Placeholder.unparsed("swiat", m.world()),
                        Placeholder.unparsed("x", String.valueOf(m.blockX())),
                        Placeholder.unparsed("y", String.valueOf(m.blockY())),
                        Placeholder.unparsed("z", String.valueOf(m.blockZ())));
            }
        }
        if (pending > 0) {
            msg.send(sender, "lista-do-odebrania", Placeholder.unparsed("ilosc", String.valueOf(pending)));
        }
    }

    private void limit(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        Player target;
        if (args.length >= 2) {
            if (!sender.hasPermission(PERM_LIMIT_OTHERS)) {
                msg.send(sender, "brak-uprawnien");
                return;
            }
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                msg.send(sender, "gracz-offline", Placeholder.unparsed("gracz", args[1]));
                return;
            }
        } else {
            if (!(sender instanceof Player player)) {
                msg.send(sender, "tylko-gracz");
                return;
            }
            if (!player.hasPermission(PERM_USE)) {
                msg.send(sender, "brak-uprawnien");
                return;
            }
            target = player;
        }
        int limit = plugin.limits().limit(target);
        msg.send(sender, "limit-info", Placeholder.unparsed("gracz", target.getName()),
                Placeholder.unparsed("liczba", String.valueOf(plugin.minions().placedCount(target.getUniqueId()))),
                Placeholder.component("limit", plugin.minions().limitComponent(limit)));
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission(PERM_RELOAD)) {
            plugin.messages().send(sender, "brak-uprawnien");
            return;
        }
        if (plugin.reloadPlugin()) {
            plugin.messages().send(sender, "przeladowano");
        } else {
            plugin.messages().send(sender, "przeladowanie-blad");
        }
    }

    private void collectPending(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "tylko-gracz");
            return;
        }
        if (!player.hasPermission(PERM_USE)) {
            plugin.messages().send(sender, "brak-uprawnien");
            return;
        }
        plugin.minions().deliverPending(player, true);
    }

    private List<String> typeIds() {
        List<String> ids = new ArrayList<>();
        for (MinionType type : plugin.types().all()) {
            if (type.isEnabled()) {
                ids.add(type.id());
            }
        }
        return ids;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("pomoc", "list", "limit", "odbierz"));
            if (sender.hasPermission(PERM_GIVE)) {
                subs.add("give");
            }
            if (sender.hasPermission(PERM_RELOAD)) {
                subs.add("reload");
            }
            return filter(subs, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (("give".equals(sub) && sender.hasPermission(PERM_GIVE))
                || ("list".equals(sub) && sender.hasPermission(PERM_LIST_OTHERS))
                || ("limit".equals(sub) && sender.hasPermission(PERM_LIMIT_OTHERS)))) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                names.add(p.getName());
            }
            return filter(names, args[1]);
        }
        if (args.length == 3 && "give".equals(sub) && sender.hasPermission(PERM_GIVE)) {
            return filter(typeIds(), args[2]);
        }
        if (args.length == 4 && "give".equals(sub) && sender.hasPermission(PERM_GIVE)) {
            return filter(List.of("1", "5", "10"), args[3]);
        }
        if (args.length == 5 && "give".equals(sub) && sender.hasPermission(PERM_GIVE)) {
            MinionType type = plugin.types().get(args[2]);
            List<String> levels = new ArrayList<>();
            if (type != null) {
                for (int i = 1; i <= type.settings().maxLevel(); i++) {
                    levels.add(String.valueOf(i));
                }
            }
            return filter(levels, args[4]);
        }
        if (args.length == 6 && "give".equals(sub) && sender.hasPermission(PERM_GIVE)) {
            return filter(new ArrayList<>(plugin.settings().rarities().keySet()), args[5]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
