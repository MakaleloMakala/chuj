package pl.skyblock.miniony.data;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Baza SQLite. Wszystkie operacje wykonywane są na jednym, dedykowanym wątku
 * (kolejność zapisów jest zachowana). Tryb WAL zapewnia spójność pliku przy crashu serwera.
 */
public final class Database {

    private static final int SCHEMA_VERSION = 2;

    private static final String UPSERT = """
            INSERT INTO minions (id, owner, owner_name, island, type, rarity, level, state, token, world, x, y, z, yaw,
                                 storage, modules, pending_delivery, created_at, last_work, link, fuel_type, fuel_ms, last_active)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                owner = excluded.owner, owner_name = excluded.owner_name, island = excluded.island,
                type = excluded.type, rarity = excluded.rarity, level = excluded.level, state = excluded.state,
                token = excluded.token, world = excluded.world, x = excluded.x, y = excluded.y, z = excluded.z,
                yaw = excluded.yaw, storage = excluded.storage, modules = excluded.modules,
                pending_delivery = excluded.pending_delivery, last_work = excluded.last_work, link = excluded.link,
                fuel_type = excluded.fuel_type, fuel_ms = excluded.fuel_ms, last_active = excluded.last_active
            """;

    private final Logger logger;
    private final ExecutorService executor;
    private Connection connection;

    public Database(Logger logger) {
        this.logger = logger;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "Miniony-Database");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Otwiera bazę i tworzy tabele. Blokuje do zakończenia (wywoływane przy starcie pluginu). */
    public void open(File file) throws Exception {
        runSync(() -> {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS minions (
                            id TEXT PRIMARY KEY,
                            owner TEXT NOT NULL,
                            owner_name TEXT,
                            island TEXT,
                            type TEXT NOT NULL,
                            rarity TEXT NOT NULL,
                            level INTEGER NOT NULL,
                            state TEXT NOT NULL,
                            token TEXT NOT NULL,
                            world TEXT,
                            x REAL, y REAL, z REAL, yaw REAL,
                            storage BLOB,
                            modules TEXT,
                            pending_delivery INTEGER NOT NULL DEFAULT 0,
                            created_at INTEGER NOT NULL,
                            last_work INTEGER NOT NULL DEFAULT 0
                        )""");
                st.execute("CREATE INDEX IF NOT EXISTS idx_minions_owner ON minions(owner)");
                st.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)");
            }
            migrate();
            return null;
        });
    }

    /** Migracja schematu: dodaje brakujące kolumny (bez utraty danych). */
    private void migrate() throws SQLException {
        java.util.Set<String> columns = new java.util.HashSet<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(minions)")) {
            while (rs.next()) {
                columns.add(rs.getString("name").toLowerCase(java.util.Locale.ROOT));
            }
        }
        String[][] added = {
                {"link", "TEXT"},
                {"fuel_type", "TEXT"},
                {"fuel_ms", "INTEGER NOT NULL DEFAULT 0"},
                {"last_active", "INTEGER NOT NULL DEFAULT 0"}
        };
        try (Statement st = connection.createStatement()) {
            for (String[] column : added) {
                if (!columns.contains(column[0])) {
                    st.execute("ALTER TABLE minions ADD COLUMN " + column[0] + " " + column[1]);
                    logger.info("Baza: dodano kolumnę '" + column[0] + "'.");
                }
            }
            st.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('schema', '" + SCHEMA_VERSION + "')");
        }
    }

    /** Odczyt wartości z tabeli meta (blokuje - tylko przy starcie). */
    public String getMeta(String key) throws Exception {
        return runSync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    /** Asynchroniczny zapis wartości w tabeli meta. */
    public void setMetaAsync(String key, String value) {
        if (executor.isShutdown()) {
            return;
        }
        try {
            executor.execute(() -> {
                try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)")) {
                    ps.setString(1, key);
                    ps.setString(2, value);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    logger.log(Level.WARNING, "Błąd zapisu meta '" + key + "'", e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // baza zamknięta
        }
    }

    /** Wczytuje wszystkie miniony. Blokuje (wywoływane przy starcie pluginu). */
    public List<MinionSnapshot> loadAll() throws Exception {
        return runSync(() -> {
            List<MinionSnapshot> result = new ArrayList<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM minions")) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    try {
                        result.add(new MinionSnapshot(
                                UUID.fromString(id),
                                UUID.fromString(rs.getString("owner")),
                                rs.getString("owner_name"),
                                parseUuid(rs.getString("island")),
                                rs.getString("type"),
                                rs.getString("rarity"),
                                rs.getInt("level"),
                                rs.getString("state"),
                                UUID.fromString(rs.getString("token")),
                                rs.getString("world"),
                                rs.getDouble("x"),
                                rs.getDouble("y"),
                                rs.getDouble("z"),
                                rs.getFloat("yaw"),
                                rs.getBytes("storage"),
                                rs.getString("modules"),
                                rs.getInt("pending_delivery") != 0,
                                rs.getLong("created_at"),
                                rs.getLong("last_work"),
                                rs.getString("link"),
                                rs.getString("fuel_type"),
                                rs.getLong("fuel_ms"),
                                rs.getLong("last_active")));
                    } catch (RuntimeException e) {
                        logger.warning("Pominięto uszkodzony rekord miniona '" + id + "': " + e.getMessage());
                    }
                }
            }
            return result;
        });
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return UUID.fromString(value);
    }

    /**
     * Zapisuje snapshoty asynchronicznie w jednej transakcji.
     * onFailure jest wywoływane na wątku bazy - wywołujący musi sam wrócić na główny wątek.
     */
    public void saveAsync(Collection<MinionSnapshot> snapshots, Consumer<Collection<MinionSnapshot>> onFailure) {
        saveAsync(snapshots, onFailure, null);
    }

    /**
     * Jak wyżej, dodatkowo onSuccess jest wywoływane (na wątku bazy) po udanym zapisie.
     */
    public void saveAsync(Collection<MinionSnapshot> snapshots, Consumer<Collection<MinionSnapshot>> onFailure,
                          Runnable onSuccess) {
        if (snapshots.isEmpty()) {
            return;
        }
        List<MinionSnapshot> copy = List.copyOf(snapshots);
        try {
            executor.execute(() -> {
                try {
                    saveNow(copy);
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                } catch (SQLException e) {
                    logger.log(Level.SEVERE, "Błąd zapisu " + copy.size() + " minionów do bazy", e);
                    if (onFailure != null) {
                        onFailure.accept(copy);
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            logger.severe("Baza jest już zamknięta - nie zapisano " + copy.size() + " minionów.");
        }
    }

    public void deleteAsync(UUID id) {
        if (executor.isShutdown()) {
            return;
        }
        executor.execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM minions WHERE id = ?")) {
                ps.setString(1, id.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Błąd usuwania miniona " + id, e);
            }
        });
    }

    private void saveNow(List<MinionSnapshot> snapshots) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(UPSERT)) {
            for (MinionSnapshot s : snapshots) {
                int i = 1;
                ps.setString(i++, s.id().toString());
                ps.setString(i++, s.owner().toString());
                ps.setString(i++, s.ownerName());
                ps.setString(i++, s.island() == null ? null : s.island().toString());
                ps.setString(i++, s.type());
                ps.setString(i++, s.rarity());
                ps.setInt(i++, s.level());
                ps.setString(i++, s.state());
                ps.setString(i++, s.token().toString());
                ps.setString(i++, s.world());
                ps.setDouble(i++, s.x());
                ps.setDouble(i++, s.y());
                ps.setDouble(i++, s.z());
                ps.setFloat(i++, s.yaw());
                ps.setBytes(i++, s.storage());
                ps.setString(i++, s.modules());
                ps.setInt(i++, s.pendingDelivery() ? 1 : 0);
                ps.setLong(i++, s.createdAt());
                ps.setLong(i++, s.lastWorkAt());
                ps.setString(i++, s.linkedChest());
                ps.setString(i++, s.fuelType());
                ps.setLong(i++, s.fuelMs());
                ps.setLong(i, s.lastActiveAt());
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException rollback) {
                e.addSuppressed(rollback);
            }
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    /**
     * Kończy pracę: czeka na wszystkie zaplanowane zapisy (maks. 30 s) i zamyka połączenie.
     */
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.severe("Zapisy do bazy nie zakończyły się w 30 sekund!");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Błąd zamykania bazy", e);
        }
    }

    private interface SqlTask<T> {
        T run() throws Exception;
    }

    private <T> T runSync(SqlTask<T> task) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(task.run());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(60, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }
}
