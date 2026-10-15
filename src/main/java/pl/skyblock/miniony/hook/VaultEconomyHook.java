package pl.skyblock.miniony.hook;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Ekonomia przez Vault. Dostawca jest pobierany przy każdym użyciu - plugin ekonomii może
 * zarejestrować się później niż Miniony albo zostać przeładowany.
 * Klasa ładowana tylko, gdy Vault jest włączony.
 */
public final class VaultEconomyHook implements EconomyHook {

    private final Logger logger;

    public VaultEconomyHook(Logger logger) {
        this.logger = logger;
    }

    private Economy economy() {
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (provider == null) {
            return null;
        }
        Economy economy = provider.getProvider();
        return economy != null && economy.isEnabled() ? economy : null;
    }

    @Override
    public boolean isAvailable() {
        return economy() != null;
    }

    @Override
    public boolean has(Player player, double amount) {
        Economy economy = economy();
        try {
            return economy != null && economy.has(player, amount);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Błąd ekonomii (has)", e);
            return false;
        }
    }

    @Override
    public boolean withdraw(Player player, double amount) {
        Economy economy = economy();
        if (economy == null) {
            return false;
        }
        try {
            EconomyResponse response = economy.withdrawPlayer(player, amount);
            return response != null && response.transactionSuccess();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Błąd ekonomii (withdraw)", e);
            return false;
        }
    }

    @Override
    public boolean deposit(Player player, double amount) {
        Economy economy = economy();
        if (economy == null) {
            return false;
        }
        try {
            EconomyResponse response = economy.depositPlayer(player, amount);
            return response != null && response.transactionSuccess();
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Błąd ekonomii (deposit) - gracz " + player.getName() + " nie otrzymał zwrotu " + amount, e);
            return false;
        }
    }

    @Override
    public boolean depositOffline(java.util.UUID player, double amount) {
        Economy economy = economy();
        if (economy == null) {
            return false;
        }
        try {
            EconomyResponse response = economy.depositPlayer(Bukkit.getOfflinePlayer(player), amount);
            return response != null && response.transactionSuccess();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Błąd ekonomii (depositOffline)", e);
            return false;
        }
    }

    @Override
    public String format(double amount) {
        Economy economy = economy();
        try {
            return economy != null ? economy.format(amount) : String.format(Locale.ROOT, "%.2f", amount);
        } catch (RuntimeException e) {
            return String.format(Locale.ROOT, "%.2f", amount);
        }
    }
}
