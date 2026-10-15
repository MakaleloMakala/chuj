package pl.skyblock.miniony.hook;

import org.bukkit.entity.Player;

import java.util.Locale;

/** Ekonomia. Gdy brak Vault lub pluginu ekonomii - {@link #isAvailable()} zwraca false. */
public interface EconomyHook {

    boolean isAvailable();

    boolean has(Player player, double amount);

    /** Pobiera kwotę. Zwraca true tylko przy potwierdzonej, udanej transakcji. */
    boolean withdraw(Player player, double amount);

    /** Zwrot kwoty (np. gdy dalsza część transakcji się nie powiodła). */
    boolean deposit(Player player, double amount);

    String format(double amount);

    /** Brak ekonomii. */
    EconomyHook NONE = new EconomyHook() {
        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public boolean has(Player player, double amount) {
            return false;
        }

        @Override
        public boolean withdraw(Player player, double amount) {
            return false;
        }

        @Override
        public boolean deposit(Player player, double amount) {
            return false;
        }

        @Override
        public String format(double amount) {
            return String.format(Locale.ROOT, "%.2f", amount);
        }
    };
}
