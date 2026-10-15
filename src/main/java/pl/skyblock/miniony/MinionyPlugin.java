package pl.skyblock.miniony;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import pl.skyblock.miniony.command.MinionCommand;
import pl.skyblock.miniony.config.ConfigReader;
import pl.skyblock.miniony.config.PluginSettings;
import pl.skyblock.miniony.data.Database;
import pl.skyblock.miniony.data.MinionSnapshot;
import pl.skyblock.miniony.gui.MinionGui;
import pl.skyblock.miniony.hook.EconomyHook;
import pl.skyblock.miniony.hook.IslandHook;
import pl.skyblock.miniony.hook.LuckPermsHook;
import pl.skyblock.miniony.hook.NexoHook;
import pl.skyblock.miniony.hook.NoIslandHook;
import pl.skyblock.miniony.hook.SuperiorSkyblockHook;
import pl.skyblock.miniony.hook.VaultEconomyHook;
import pl.skyblock.miniony.limit.LimitService;
import pl.skyblock.miniony.listener.ChunkListener;
import pl.skyblock.miniony.listener.EntityListener;
import pl.skyblock.miniony.listener.PlaceListener;
import pl.skyblock.miniony.listener.PlayerListener;
import pl.skyblock.miniony.gui.ModuleGui;
import pl.skyblock.miniony.manager.MinionItems;
import pl.skyblock.miniony.manager.ModuleService;
import pl.skyblock.miniony.manager.MinionManager;
import pl.skyblock.miniony.manager.MinionVisuals;
import pl.skyblock.miniony.manager.UpgradeService;
import pl.skyblock.miniony.message.Messages;
import pl.skyblock.miniony.type.CollectorType;
import pl.skyblock.miniony.type.FarmerType;
import pl.skyblock.miniony.type.FisherType;
import pl.skyblock.miniony.type.LumberjackType;
import pl.skyblock.miniony.type.MinerType;
import pl.skyblock.miniony.type.SlayerType;
import pl.skyblock.miniony.type.MinionTypeRegistry;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;

public final class MinionyPlugin extends JavaPlugin {

    private static final int CONFIG_VERSION = 4;

    private PluginSettings settings;
    private Messages messages;
    private MinionTypeRegistry types;
    private Database database;
    private MinionManager minions;
    private MinionVisuals visuals;
    private MinionItems items;
    private MinionGui gui;
    private LimitService limits;
    private IslandHook islands = new NoIslandHook();
    private LuckPermsHook luckPerms;
    private EconomyHook economy = EconomyHook.NONE;
    private NexoHook nexo = NexoHook.disabled();
    private UpgradeService upgrades;
    private ModuleService modules;
    private ModuleGui moduleGui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        types = new MinionTypeRegistry();
        types.register(new MinerType());
        types.register(new LumberjackType());
        types.register(new FarmerType());
        types.register(new FisherType());
        types.register(new SlayerType());
        types.register(new CollectorType());
        messages = new Messages(getLogger());
        if (!loadConfiguration(true)) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        items = new MinionItems(this);
        visuals = new MinionVisuals(this);
        limits = new LimitService(this);
        setupHooks();

        database = new Database(getLogger());
        List<MinionSnapshot> snapshots;
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
                throw new IOException("Nie można utworzyć folderu " + getDataFolder());
            }
            database.open(new File(getDataFolder(), settings.databaseFile()));
            snapshots = database.loadAll();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Nie udało się otworzyć bazy danych SQLite - plugin zostaje wyłączony.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        long heartbeat = 0;
        try {
            String value = database.getMeta("heartbeat");
            heartbeat = value == null ? 0 : Long.parseLong(value);
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Nie udało się odczytać heartbeat z bazy - praca offline po restarcie nie zostanie naliczona.", e);
        }
        modules = new ModuleService(this);
        upgrades = new UpgradeService(this);
        minions = new MinionManager(this);
        minions.load(snapshots, heartbeat);
        gui = new MinionGui(this);
        moduleGui = new ModuleGui(this);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(gui, this);
        pm.registerEvents(moduleGui, this);
        pm.registerEvents(new PlaceListener(this), this);
        pm.registerEvents(new EntityListener(this), this);
        pm.registerEvents(new ChunkListener(this), this);
        pm.registerEvents(new PlayerListener(this), this);
        pm.registerEvents(new HookListener(), this);

        PluginCommand command = getCommand("minion");
        if (command != null) {
            MinionCommand executor = new MinionCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        } else {
            getLogger().severe("Brak komendy 'minion' w plugin.yml!");
        }

        minions.start();
        for (Player player : Bukkit.getOnlinePlayers()) {
            limits.refresh(player);
        }
        getLogger().info("Miniony włączone. Integracje: SuperiorSkyblock2=" + islands.isEnabled()
                + ", LuckPerms=" + (luckPerms != null) + ", Vault=" + (economy != EconomyHook.NONE)
                + ", Nexo=" + nexo.isAvailable());
    }

    private void setupHooks() {
        setupIslands(true);
        setupLuckPerms(true);
        setupEconomy(true);
        setupNexo();
    }

    private void setupIslands(boolean logMissing) {
        if (islands != null) {
            islands.shutdown();
        }
        islands = new NoIslandHook();
        if (getServer().getPluginManager().isPluginEnabled("SuperiorSkyblock2")) {
            try {
                islands = new SuperiorSkyblockHook(this);
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "Nie udało się podłączyć do SuperiorSkyblock2 - sprawdzanie wysp wyłączone.", t);
                islands = new NoIslandHook();
            }
        } else if (logMissing) {
            getLogger().info("Brak SuperiorSkyblock2 - sprawdzanie wysp jest wyłączone.");
        }
    }

    private void setupLuckPerms(boolean logMissing) {
        if (luckPerms != null) {
            try {
                luckPerms.shutdown();
            } catch (Throwable ignored) {
                // LuckPerms mógł zostać już wyłączony
            }
        }
        luckPerms = null;
        if (getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
            try {
                luckPerms = LuckPermsHook.create(this);
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "Nie udało się podłączyć do LuckPerms - limity liczone z uprawnień Bukkit.", t);
            }
        } else if (logMissing) {
            getLogger().info("Brak LuckPerms - limity liczone z uprawnień Bukkit, bez automatycznego odświeżania.");
        }
        limits.setLuckPerms(luckPerms);
        limits.clear();
    }

    private void setupEconomy(boolean logMissing) {
        economy = EconomyHook.NONE;
        if (getServer().getPluginManager().isPluginEnabled("Vault")) {
            try {
                economy = new VaultEconomyHook(getLogger());
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "Nie udało się podłączyć do Vault - koszty pieniężne są niedostępne.", t);
            }
        } else if (logMissing) {
            getLogger().info("Brak Vault - ulepszenia za pieniądze są niedostępne.");
        }
    }

    private void setupNexo() {
        nexo = NexoHook.disabled();
        Plugin nexoPlugin = getServer().getPluginManager().getPlugin("Nexo");
        if (nexoPlugin != null && nexoPlugin.isEnabled()) {
            nexo = NexoHook.create(nexoPlugin, getLogger());
        }
    }

    /**
     * Reakcja na włączenie/wyłączenie pluginów integracji w trakcie działania serwera
     * (np. PlugMan) - zamiast wyjątków integracja jest wyłączana albo podłączana ponownie.
     */
    private final class HookListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onPluginDisable(PluginDisableEvent event) {
            String name = event.getPlugin().getName();
            switch (name) {
                case "SuperiorSkyblock2" -> {
                    islands.shutdown();
                    islands = new NoIslandHook();
                    getLogger().warning("SuperiorSkyblock2 został wyłączony - sprawdzanie wysp wyłączone.");
                }
                case "LuckPerms" -> {
                    luckPerms = null;
                    limits.setLuckPerms(null);
                    limits.clear();
                    getLogger().warning("LuckPerms został wyłączony - limity liczone z uprawnień Bukkit.");
                }
                case "Vault" -> {
                    economy = EconomyHook.NONE;
                    getLogger().warning("Vault został wyłączony - koszty pieniężne i auto-sprzedaż niedostępne.");
                }
                case "Nexo" -> {
                    nexo = NexoHook.disabled();
                    getLogger().warning("Nexo został wyłączony - przedmioty Nexo w kosztach niedostępne.");
                }
                default -> {
                    // inne pluginy nas nie dotyczą
                }
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onPluginEnable(PluginEnableEvent event) {
            switch (event.getPlugin().getName()) {
                case "SuperiorSkyblock2" -> {
                    setupIslands(false);
                    getLogger().info("Podłączono SuperiorSkyblock2.");
                }
                case "LuckPerms" -> {
                    setupLuckPerms(false);
                    getLogger().info("Podłączono LuckPerms.");
                }
                case "Vault" -> setupEconomy(false);
                case "Nexo" -> setupNexo();
                default -> {
                    // inne pluginy nas nie dotyczą
                }
            }
        }
    }

    @Override
    public void onDisable() {
        if (gui != null) {
            gui.closeAll();
        }
        if (moduleGui != null) {
            moduleGui.closeAll();
        }
        if (minions != null) {
            minions.shutdown();
        }
        if (luckPerms != null) {
            try {
                luckPerms.shutdown();
            } catch (Throwable ignored) {
                // LuckPerms mógł zostać już wyłączony
            }
            luckPerms = null;
        }
        if (islands != null) {
            islands.shutdown();
        }
        if (database != null) {
            database.close();
        }
        HandlerList.unregisterAll(this);
        getServer().getScheduler().cancelTasks(this);
    }

    /**
     * Wczytuje config.yml. Przy błędzie składni: przy starcie używa wartości domyślnych,
     * przy przeładowaniu zostawia poprzednie ustawienia i zwraca false.
     */
    private boolean loadConfiguration(boolean startup) {
        File file = new File(getDataFolder(), "config.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("Błąd w pliku config.yml: " + e.getMessage());
            if (!startup) {
                return false;
            }
            getLogger().severe("Używam domyślnej konfiguracji. Popraw plik i wpisz /minion reload.");
            yaml = new YamlConfiguration();
        }
        try (InputStream in = getResource("config.yml")) {
            if (in != null) {
                yaml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Nie można odczytać domyślnego config.yml z pliku jar", e);
        }
        ConfigReader reader = new ConfigReader(yaml, getLogger());
        int version = yaml.getInt("wersja-configu", 0);
        if (version < CONFIG_VERSION && yaml.contains("wersja-configu", true)) {
            getLogger().warning("[Konfiguracja] config.yml jest w starszej wersji (" + version + ", aktualna: " + CONFIG_VERSION
                    + "). Brakujące wartości zostaną wzięte z domyślnego configu - porównaj plik z nowym config.yml"
                    + " (np. usuń go, aby wygenerować nowy) i ustaw wersja-configu: " + CONFIG_VERSION + ".");
        }
        PluginSettings newSettings = PluginSettings.read(reader);
        if (settings != null && !settings.databaseFile().equals(newSettings.databaseFile())) {
            getLogger().warning("[Konfiguracja] Zmiana 'baza-danych.plik' wymaga restartu serwera.");
        }
        types.loadAll(reader);
        messages.load(yaml);
        settings = newSettings;
        if (reader.warnings() > 0) {
            getLogger().warning("Konfiguracja wczytana z ostrzeżeniami (" + reader.warnings() + ") - szczegóły wyżej.");
        }
        return true;
    }

    /** /minion reload */
    public boolean reloadPlugin() {
        gui.closeAll();
        moduleGui.closeAll();
        if (!loadConfiguration(false)) {
            return false;
        }
        modules.reload();
        limits.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            limits.refresh(player);
        }
        minions.reload();
        return true;
    }

    public PluginSettings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public MinionTypeRegistry types() {
        return types;
    }

    public Database database() {
        return database;
    }

    public MinionManager minions() {
        return minions;
    }

    public MinionVisuals visuals() {
        return visuals;
    }

    public MinionItems items() {
        return items;
    }

    public MinionGui gui() {
        return gui;
    }

    public LimitService limits() {
        return limits;
    }

    public IslandHook islands() {
        return islands;
    }

    public EconomyHook economy() {
        return economy;
    }

    public NexoHook nexo() {
        return nexo;
    }

    public UpgradeService upgrades() {
        return upgrades;
    }

    public ModuleService modules() {
        return modules;
    }

    public ModuleGui moduleGui() {
        return moduleGui;
    }
}
