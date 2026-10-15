package pl.skyblock.miniony.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import pl.skyblock.miniony.MinionyPlugin;
import pl.skyblock.miniony.model.Minion;
import pl.skyblock.miniony.model.MinionState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Ochrona encji minionów i otwieranie GUI prawym kliknięciem. */
public final class EntityListener implements Listener {

    private final MinionyPlugin plugin;
    /** gracz -> tick ostatniego otwarcia GUI (dla ArmorStanda serwer wysyła dwa zdarzenia na jedno kliknięcie) */
    private final Map<UUID, Integer> lastOpenTick = new HashMap<>();

    public EntityListener(MinionyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractAt(PlayerInteractAtEntityEvent event) {
        handleInteract(event.getPlayer(), event.getRightClicked(), event.getHand(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event instanceof PlayerInteractAtEntityEvent) {
            return;
        }
        handleInteract(event.getPlayer(), event.getRightClicked(), event.getHand(), event);
    }

    private void handleInteract(Player player, Entity entity, EquipmentSlot hand, PlayerInteractEntityEvent event) {
        UUID id = plugin.visuals().minionIdOf(entity);
        if (id == null) {
            return;
        }
        event.setCancelled(true);
        if (hand != EquipmentSlot.HAND) {
            return; // jedno otwarcie na kliknięcie
        }
        Minion minion = plugin.minions().get(id);
        if (minion == null || minion.state() != MinionState.PLACED || !plugin.visuals().isTracked(minion, entity)) {
            entity.remove(); // osierocona encja
            return;
        }
        if (!plugin.minions().canAccess(player, minion)) {
            plugin.messages().send(player, "brak-dostepu");
            return;
        }
        int tick = Bukkit.getCurrentTick();
        Integer last = lastOpenTick.put(player.getUniqueId(), tick);
        if (last != null && last == tick) {
            return;
        }
        plugin.gui().open(player, minion, 0);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastOpenTick.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onManipulate(PlayerArmorStandManipulateEvent event) {
        if (plugin.visuals().isMinionEntity(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (plugin.visuals().isMinionEntity(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** MONITOR + ignoreCancelled: drop trafia do magazynu tylko, gdy śmierć faktycznie nastąpiła. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        plugin.minions().onEntityDeath(event);
    }

    /** Zniszczony przedmiot miniona (lawa, ogień, wybuch, pustka, zniknięcie) - minion przestaje istnieć. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemRemoved(EntityRemoveEvent event) {
        if (!(event.getEntity() instanceof Item item)) {
            return;
        }
        EntityRemoveEvent.Cause cause = event.getCause();
        if (cause != EntityRemoveEvent.Cause.DEATH && cause != EntityRemoveEvent.Cause.DESPAWN
                && cause != EntityRemoveEvent.Cause.OUT_OF_WORLD) {
            return;
        }
        ItemStack stack = item.getItemStack();
        if (plugin.items().isMinionItem(stack)) {
            plugin.minions().onMinionItemDestroyed(stack, cause.name());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCombust(EntityCombustEvent event) {
        if (plugin.visuals().isMinionEntity(event.getEntity())) {
            event.setCancelled(true);
        }
    }
}
