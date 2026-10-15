package pl.skyblock.miniony.manager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import pl.skyblock.miniony.MinionyPlugin;
import pl.skyblock.miniony.config.CostItem;
import pl.skyblock.miniony.config.GuiSettings;
import pl.skyblock.miniony.config.LevelSettings;
import pl.skyblock.miniony.config.UpgradeCost;
import pl.skyblock.miniony.hook.EconomyHook;
import pl.skyblock.miniony.hook.NexoHook;
import pl.skyblock.miniony.message.Messages;
import pl.skyblock.miniony.message.Text;
import pl.skyblock.miniony.model.Minion;
import pl.skyblock.miniony.model.MinionState;
import pl.skyblock.miniony.type.MinionType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ulepszanie minionów. Cała operacja (sprawdzenie, pobranie kosztu, zmiana poziomu) odbywa się
 * w jednym ticku na głównym wątku, więc stan ekwipunku nie może się zmienić pomiędzy sprawdzeniem a pobraniem.
 * <ol>
 *     <li>walidacja: poziom, dostęp, dostępność ekonomii/Nexo, ilość pieniędzy i przedmiotów (plan pobrania),</li>
 *     <li>pobranie pieniędzy (tylko potwierdzona transakcja),</li>
 *     <li>ponowna weryfikacja planu i pobranie przedmiotów - przy niezgodności zwrot pieniędzy i przerwanie,</li>
 *     <li>zmiana poziomu i natychmiastowy zapis.</li>
 * </ol>
 */
public final class UpgradeService {

    private static final long CLICK_COOLDOWN_MS = 1000L;

    private final MinionyPlugin plugin;
    /** gracz -> czas ostatniej próby ulepszenia (ochrona przed przypadkowym podwójnym kliknięciem) */
    private final Map<UUID, Long> lastAttempt = new HashMap<>();

    public UpgradeService(MinionyPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean canUpgrade(Player player, Minion minion) {
        return plugin.settings().upgradeOwnerOnly()
                ? plugin.minions().canPickup(player, minion)
                : plugin.minions().canAccess(player, minion);
    }

    public void forget(UUID player) {
        lastAttempt.remove(player);
    }

    public void upgrade(Player player, Minion minion) {
        long time = System.currentTimeMillis();
        Long last = lastAttempt.put(player.getUniqueId(), time);
        if (last != null && time - last < CLICK_COOLDOWN_MS) {
            return;
        }
        Messages msg = plugin.messages();
        if (minion.state() != MinionState.PLACED || plugin.minions().get(minion.id()) != minion) {
            msg.send(player, "minion-niedostepny");
            return;
        }
        if (!canUpgrade(player, minion)) {
            msg.send(player, "tylko-wlasciciel-ulepsza");
            return;
        }
        MinionType type = plugin.types().get(minion.typeId());
        if (type == null || !type.isEnabled()) {
            msg.send(player, "typ-wylaczony");
            return;
        }
        if (minion.level() >= type.settings().maxLevel()) {
            msg.send(player, "maks-poziom");
            return;
        }
        LevelSettings next = type.settings().level(minion.level() + 1);
        UpgradeCost cost = next.cost();
        if (!cost.valid()) {
            msg.send(player, "koszt-niedostepny");
            return;
        }
        EconomyHook economy = plugin.economy();
        if (cost.money() > 0) {
            if (!economy.isAvailable()) {
                msg.send(player, "ekonomia-niedostepna");
                return;
            }
            if (!economy.has(player, cost.money())) {
                msg.send(player, "brak-pieniedzy", Placeholder.unparsed("kwota", economy.format(cost.money())));
                return;
            }
        }
        NexoHook nexo = plugin.nexo();
        for (CostItem item : cost.items()) {
            if (item.isNexo() && (!nexo.isAvailable() || !nexo.exists(item.nexoId()))) {
                plugin.getLogger().warning("Koszt ulepszenia typu '" + type.id() + "' na poziom " + next.level()
                        + " wymaga przedmiotu Nexo '" + item.nexoId() + "', który jest niedostępny.");
                msg.send(player, "koszt-niedostepny");
                return;
            }
        }

        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getStorageContents();
        Map<Integer, Integer> plan = new LinkedHashMap<>();
        List<Component> missing = new ArrayList<>();
        if (!plan(contents, cost.items(), plan, missing)) {
            msg.send(player, "brak-przedmiotow", Placeholder.component("brakujace",
                    Component.join(JoinConfiguration.commas(true), missing)));
            return;
        }

        if (cost.money() > 0 && !economy.withdraw(player, cost.money())) {
            msg.send(player, "blad-transakcji");
            return;
        }
        // Ponowna weryfikacja (ten sam tick - powinna zawsze przejść) i pobranie przedmiotów.
        ItemStack[] current = inventory.getStorageContents();
        for (Map.Entry<Integer, Integer> entry : plan.entrySet()) {
            ItemStack expected = contents[entry.getKey()];
            ItemStack now = current[entry.getKey()];
            if (now == null || expected == null || !now.isSimilar(expected) || now.getAmount() < entry.getValue()) {
                if (cost.money() > 0 && !economy.deposit(player, cost.money())) {
                    plugin.getLogger().severe("Nie udało się zwrócić " + cost.money() + " graczowi " + player.getName()
                            + " po nieudanym ulepszeniu miniona " + minion.id() + "!");
                }
                msg.send(player, "blad-transakcji");
                return;
            }
        }
        for (Map.Entry<Integer, Integer> entry : plan.entrySet()) {
            int slot = entry.getKey();
            ItemStack stack = current[slot];
            int remaining = stack.getAmount() - entry.getValue();
            if (remaining <= 0) {
                inventory.setItem(slot, null);
            } else {
                ItemStack rest = stack.clone();
                rest.setAmount(remaining);
                inventory.setItem(slot, rest);
            }
        }

        minion.setLevel(next.level());
        plugin.minions().applyLevelChange(minion);
        // Kolejność: najpierw ekwipunek gracza (pobrany koszt), potem minion - przy crashu co najwyżej
        // strata kosztu, nigdy darmowe ulepszenie.
        player.saveData();
        plugin.minions().storageChangedByPlayer(minion);
        msg.send(player, "ulepszono", MinionPlaceholders.of(plugin, minion));
        plugin.getLogger().info("Gracz " + player.getName() + " ulepszył miniona " + minion.id() + " (" + type.id()
                + ") do poziomu " + next.level() + ".");
    }

    /**
     * Wylicza plan pobrania przedmiotów (slot -> ilość). Zwraca false i uzupełnia listę brakujących,
     * jeśli przedmiotów jest za mało.
     */
    private boolean plan(ItemStack[] contents, List<CostItem> items, Map<Integer, Integer> plan, List<Component> missing) {
        int[] available = new int[contents.length];
        for (int i = 0; i < contents.length; i++) {
            available[i] = contents[i] == null ? 0 : contents[i].getAmount();
        }
        for (CostItem cost : items) {
            int need = cost.amount();
            for (int i = 0; i < contents.length && need > 0; i++) {
                if (available[i] <= 0 || !matches(cost, contents[i])) {
                    continue;
                }
                int take = Math.min(need, available[i]);
                available[i] -= take;
                need -= take;
                plan.merge(i, take, Integer::sum);
            }
            if (need > 0) {
                missing.add(Text.parse("<white><ilosc>x </white><nazwa>",
                        Placeholder.unparsed("ilosc", String.valueOf(need)),
                        Placeholder.component("nazwa", itemName(cost))));
            }
        }
        return missing.isEmpty();
    }

    private boolean matches(CostItem cost, ItemStack stack) {
        if (stack == null || stack.getType().isAir() || plugin.items().isMinionItem(stack)) {
            return false;
        }
        if (cost.isNexo()) {
            return cost.nexoId().equalsIgnoreCase(plugin.nexo().idOf(stack));
        }
        // Tylko "czyste" przedmioty - bez własnej nazwy, lore, enchantów i danych pluginów.
        return stack.isSimilar(new ItemStack(cost.material()));
    }

    /** Nazwa przedmiotu z kosztu (do GUI i wiadomości). */
    public Component itemName(CostItem cost) {
        if (cost.isNexo()) {
            ItemStack stack = plugin.nexo().create(cost.nexoId());
            return stack != null ? stack.effectiveName() : Component.text(cost.nexoId());
        }
        return new ItemStack(cost.material()).effectiveName();
    }

    /** Linie kosztu do lore przycisku ulepszenia. */
    public List<Component> costLines(UpgradeCost cost) {
        GuiSettings gui = plugin.settings().gui();
        List<Component> lines = new ArrayList<>();
        if (cost.isFree()) {
            lines.add(Text.item(gui.costFreeLine()));
            return lines;
        }
        if (cost.money() > 0) {
            lines.add(Text.item(gui.costMoneyLine(), Placeholder.unparsed("kwota", plugin.economy().format(cost.money()))));
        }
        for (CostItem item : cost.items()) {
            TagResolver resolver = TagResolver.resolver(
                    Placeholder.unparsed("ilosc", String.valueOf(item.amount())),
                    Placeholder.component("nazwa", itemName(item)));
            lines.add(Text.item(gui.costItemLine(), resolver));
        }
        return lines;
    }
}
