package pl.skyblock.miniony.hook;

import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Integracja z Nexo przez refleksję (Nexo jest opcjonalny - brak zależności w czasie kompilacji).
 * Używane metody: {@code NexoItems.idFromItem(ItemStack): String}, {@code NexoItems.exists(String): boolean},
 * {@code NexoItems.itemFromId(String): ItemBuilder}, {@code ItemBuilder.build(): ItemStack}.
 */
public final class NexoHook {

    private static final NexoHook DISABLED = new NexoHook(null, null, null, null, null);

    private final Logger logger;
    private final Method idFromItem;
    private final Method exists;
    private final Method itemFromId;
    private final Method build;
    private boolean errorReported;

    private NexoHook(Logger logger, Method idFromItem, Method exists, Method itemFromId, Method build) {
        this.logger = logger;
        this.idFromItem = idFromItem;
        this.exists = exists;
        this.itemFromId = itemFromId;
        this.build = build;
    }

    public static NexoHook disabled() {
        return DISABLED;
    }

    /** Tworzy hook; przy niezgodnej wersji Nexo zwraca wyłączony hook z ostrzeżeniem. */
    public static NexoHook create(Plugin nexo, Logger logger) {
        try {
            ClassLoader loader = nexo.getClass().getClassLoader();
            Class<?> items = Class.forName("com.nexomc.nexo.api.NexoItems", true, loader);
            Class<?> builder = Class.forName("com.nexomc.nexo.items.ItemBuilder", true, loader);
            return new NexoHook(logger,
                    items.getMethod("idFromItem", ItemStack.class),
                    items.getMethod("exists", String.class),
                    items.getMethod("itemFromId", String.class),
                    builder.getMethod("build"));
        } catch (ReflectiveOperationException | LinkageError e) {
            logger.warning("Nieobsługiwana wersja Nexo (" + e + ") - przedmioty Nexo w kosztach są niedostępne.");
            return DISABLED;
        }
    }

    public boolean isAvailable() {
        return idFromItem != null;
    }

    /** Id przedmiotu Nexo albo null (zwykły przedmiot lub Nexo niedostępne). */
    public String idOf(ItemStack item) {
        if (!isAvailable() || item == null || item.getType().isAir()) {
            return null;
        }
        try {
            Object id = idFromItem.invoke(null, item);
            return id == null ? null : id.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            report(e);
            return null;
        }
    }

    public boolean exists(String id) {
        if (!isAvailable()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(exists.invoke(null, id));
        } catch (ReflectiveOperationException | RuntimeException e) {
            report(e);
            return false;
        }
    }

    /** Przedmiot Nexo do wyświetlenia (nazwa w koszcie) albo null. */
    public ItemStack create(String id) {
        if (!isAvailable()) {
            return null;
        }
        try {
            Object itemBuilder = itemFromId.invoke(null, id);
            if (itemBuilder == null) {
                return null;
            }
            Object stack = build.invoke(itemBuilder);
            return stack instanceof ItemStack itemStack ? itemStack : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            report(e);
            return null;
        }
    }

    private void report(Exception e) {
        if (!errorReported && logger != null) {
            errorReported = true;
            logger.log(Level.WARNING, "Błąd integracji z Nexo", e);
        }
    }
}
