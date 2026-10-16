package cloud.mineplay.mobarena.storage;

import cloud.mineplay.mobarena.equipment.SnapshotStore;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;

/** Explicit, offline, empty-target migration. Never changes source rows or automatically switches configuration. */
public final class StorageMigration {
    private static final List<Table> TABLES = List.of(
            new Table("sessions", "session_id", false),
            new Table("participants", "session_id,player_id", false),
            new Table("settlements", "settlement_id", false),
            new Table("settlement_audit", "id", false),
            new Table("equipment_snapshots", "player_uuid", true));
    private static final Set<String> DECIMALS = Set.of("prize_pot", "unallocated_pot", "share", "gross_amount", "intended_amount");

    private StorageMigration() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        boolean stopped = false;
        for (int index = 0; index < args.length; index++) {
            if (args[index].equals("--server-stopped")) { stopped = true; continue; }
            if (!Set.of("--source-config", "--target-config", "--source-dir", "--target-dir").contains(args[index]) || index + 1 == args.length) {
                throw new IllegalArgumentException("Usage: --source-config file --target-config file --source-dir directory --target-dir directory --server-stopped");
            }
            String key = args[index];
            if (options.put(key, args[++index]) != null) throw new IllegalArgumentException("Duplicate option " + key);
        }
        if (!stopped) throw new IllegalArgumentException("Stop all game servers and writers using either backend, then explicitly pass --server-stopped");
        for (String key : List.of("--source-config", "--target-config", "--source-dir", "--target-dir")) {
            if (!options.containsKey(key)) throw new IllegalArgumentException("Missing " + key);
        }
        var source = load(Path.of(options.get("--source-config")), Path.of(options.get("--source-dir")));
        var target = load(Path.of(options.get("--target-config")), Path.of(options.get("--target-dir")));
        MigrationReport report = migrate(source, target, true);
        System.out.println("Migration verified. Source preserved. Review the counts below, back up both backends, then change the server storage configuration.");
        report.rows().forEach((table, count) -> System.out.println(table + ": " + count + " rows; SHA-256 " + report.sha256().get(table)));
        System.out.println("PROCESSING payments remain PROCESSING and will recover to UNCERTAIN at plugin startup. Never run both copies as active servers.");
    }

    private static DatabaseSettings load(Path config, Path directory) throws Exception {
        if (!Files.isRegularFile(config)) throw new IllegalArgumentException("Configuration file not found: " + config);
        var yaml = new YamlConfiguration();
        yaml.load(config.toFile());
        return DatabaseSettings.load(yaml.getConfigurationSection("storage"), directory);
    }

    public static MigrationReport migrate(DatabaseSettings source, DatabaseSettings target, boolean serverStopped) throws Exception {
        if (!serverStopped) throw new IllegalArgumentException("Offline migration requires all servers and writers to be stopped");
        rejectSameBackend(source, target);
        for (DatabaseSettings backend : List.of(source, target)) {
            if (!new SettlementOutbox(backend.directory().resolve("settlement-outbox")).entries().isEmpty()) {
                throw new IllegalArgumentException("Undrained settlement outbox in " + backend.directory() + "; restore the source connection and let the plugin drain it before migrating");
            }
        }
        if (!source.mysql()) {
            for (String filename : List.of("mobarena.db", "equipment.db")) {
                if (!Files.isRegularFile(source.directory().resolve(filename))) throw new IllegalArgumentException("Missing source database: " + filename);
            }
        }
        // Open/validate the source without running schema updates or recovery against it.
        try (Connections from = Connections.open(source, false)) {
            validateSource(from, source.mysql());
            // Empty existing schemas are acceptable; populated targets are never overwritten.
            try (Connections preflight = Connections.open(target, false)) { requireEmpty(preflight); }
            try (var ledger = new SettlementStore(target); var snapshots = new SnapshotStore(target)) { /* initialize only the target schema */ }
            try (Connections to = Connections.open(target, true)) {
                requireEmpty(to);
                for (Connection connection : to.all()) guard(connection, "RUNNING");
                var counts = new LinkedHashMap<String, Long>();
                var digests = new LinkedHashMap<String, String>();
                try {
                    for (Connection connection : from.all()) connection.setAutoCommit(false);
                    for (Connection connection : to.all()) connection.setAutoCommit(false);
                    for (Table table : TABLES) {
                        Connection sourceConnection = from.forTable(table);
                        Connection targetConnection = to.forTable(table);
                        List<String> columns = columns(sourceConnection, table.name());
                        long rows = copy(sourceConnection, targetConnection, table, columns);
                        Fingerprint before = fingerprint(sourceConnection, table, columns);
                        Fingerprint after = fingerprint(targetConnection, table, columns);
                        if (rows != before.rows() || !before.equals(after)) throw new SQLException("Readback mismatch for " + table.name());
                        counts.put(table.name(), rows);
                        digests.put(table.name(), after.sha256());
                    }
                    // SQLite uses two files; the durable guards keep either backend unusable after a partial commit.
                    for (Connection connection : to.all()) connection.commit();
                    for (Connection connection : to.all()) connection.setAutoCommit(true);
                    for (Connection connection : to.all()) guard(connection, "COMPLETE");
                    for (Connection connection : from.all()) connection.rollback();
                    return new MigrationReport(Map.copyOf(counts), Map.copyOf(digests));
                } catch (Exception exception) {
                    for (Connection connection : to.all()) {
                        try { connection.rollback(); connection.setAutoCommit(true); guard(connection, "FAILED"); }
                        catch (SQLException rollback) { exception.addSuppressed(rollback); }
                    }
                    throw exception;
                }
            }
        }
    }

    private static void rejectSameBackend(DatabaseSettings source, DatabaseSettings target) throws Exception {
        boolean same = source.mysql() && target.mysql() && source.host().equalsIgnoreCase(target.host())
                && source.port() == target.port() && source.database().equalsIgnoreCase(target.database());
        if (!source.mysql() && !target.mysql()) {
            Path from = source.directory().toAbsolutePath().normalize();
            Path to = target.directory().toAbsolutePath().normalize();
            same = from.equals(to) || (Files.exists(from) && Files.exists(to) && Files.isSameFile(from, to));
        }
        if (same) throw new IllegalArgumentException("Source and destination must be different databases");
    }

    private static void validateSource(Connections connections, boolean mysql) throws SQLException {
        for (Table table : TABLES) if (!exists(connections.forTable(table), table.name())) throw new SQLException("Source table is missing: " + table.name());
        for (var pair : Map.of("ledger", 3, "equipment", 1).entrySet()) {
            Connection connection = pair.getKey().equals("equipment") ? connections.equipment : connections.ledger;
            String sql = mysql ? "SELECT version FROM mobarena_schema WHERE component='" + pair.getKey() + "'" : "PRAGMA user_version";
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
                if (!rows.next() || rows.getInt(1) != pair.getValue()) {
                    throw new SQLException("Source schema is not current for " + pair.getKey() + "; run the matching plugin version and stop it before migration");
                }
            }
        }
    }

    private static void requireEmpty(Connections connections) throws SQLException {
        for (Table table : TABLES) {
            Connection connection = connections.forTable(table);
            if (!exists(connection, table.name())) continue;
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table.name())) {
                if (rows.next() && rows.getLong(1) != 0) throw new SQLException("Destination is not empty: " + table.name() + ". Nothing was copied.");
            }
        }
    }

    private static boolean exists(Connection connection, String table) throws SQLException {
        try (var tables = connection.getMetaData().getTables(connection.getCatalog(), null, table, new String[]{"TABLE"})) { return tables.next(); }
    }

    private static void guard(Connection connection, String state) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS mobarena_migration_guard(guard_id INTEGER PRIMARY KEY,state VARCHAR(16) NOT NULL,updated_at VARCHAR(40) NOT NULL)");
        }
        try (var update = connection.prepareStatement("UPDATE mobarena_migration_guard SET state=?,updated_at=? WHERE guard_id=1")) {
            update.setString(1, state); update.setString(2, Instant.now().toString());
            if (update.executeUpdate() == 0) {
                try (var insert = connection.prepareStatement("INSERT INTO mobarena_migration_guard VALUES(1,?,?)")) {
                    insert.setString(1, state); insert.setString(2, Instant.now().toString()); insert.executeUpdate();
                }
            }
        }
    }

    private static List<String> columns(Connection connection, String table) throws SQLException {
        var columns = new ArrayList<String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM " + table + " WHERE 1=0")) {
            for (int index = 1; index <= rows.getMetaData().getColumnCount(); index++) columns.add(rows.getMetaData().getColumnName(index));
        }
        return columns;
    }

    private static long copy(Connection from, Connection to, Table table, List<String> columns) throws SQLException {
        String names = String.join(",", columns);
        String placeholders = String.join(",", java.util.Collections.nCopies(columns.size(), "?"));
        long count = 0;
        try (var read = from.createStatement(); var rows = read.executeQuery("SELECT " + names + " FROM " + table.name() + " ORDER BY " + table.order());
             var insert = to.prepareStatement("INSERT INTO " + table.name() + "(" + names + ") VALUES(" + placeholders + ")")) {
            while (rows.next()) {
                for (int index = 1; index <= columns.size(); index++) {
                    Object value = rows.getObject(index);
                    if (value == null) insert.setNull(index, Types.NULL);
                    else if (isBinary(columns.get(index - 1))) insert.setBytes(index, rows.getBytes(index));
                    else insert.setObject(index, value);
                }
                insert.addBatch();
                count++;
                if (count % 100 == 0) insert.executeBatch();
            }
            insert.executeBatch();
        }
        return count;
    }

    private static Fingerprint fingerprint(Connection connection, Table table, List<String> columns) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count = 0;
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT " + String.join(",", columns) + " FROM " + table.name() + " ORDER BY " + table.order())) {
            while (rows.next()) {
                count++;
                for (int index = 1; index <= columns.size(); index++) {
                    String column = columns.get(index - 1);
                    byte[] value;
                    if (rows.getObject(index) == null) { digest.update(ByteBuffer.allocate(4).putInt(-1).array()); continue; }
                    if (isBinary(column)) value = rows.getBytes(index);
                    else if (DECIMALS.contains(column)) value = new BigDecimal(rows.getString(index)).stripTrailingZeros().toPlainString().getBytes(StandardCharsets.UTF_8);
                    else value = rows.getString(index).getBytes(StandardCharsets.UTF_8);
                    digest.update(ByteBuffer.allocate(4).putInt(value.length).array()); digest.update(value);
                }
            }
        }
        return new Fingerprint(count, HexFormat.of().formatHex(digest.digest()));
    }

    private static boolean isBinary(String column) { return column.equals("original") || column.equals("restore_target"); }
    public record MigrationReport(Map<String, Long> rows, Map<String, String> sha256) { }
    private record Fingerprint(long rows, String sha256) { }
    private record Table(String name, String order, boolean equipment) { }

    private static final class Connections implements AutoCloseable {
        final Connection ledger;
        final Connection equipment;
        Connections(Connection ledger, Connection equipment) { this.ledger = ledger; this.equipment = equipment; }
        static Connections open(DatabaseSettings settings, boolean migrating) throws Exception {
            Connection ledger = migrating ? settings.connectForMigration("mobarena.db") : settings.connect("mobarena.db");
            try {
                Connection equipment = settings.mysql() ? ledger : (migrating ? settings.connectForMigration("equipment.db") : settings.connect("equipment.db"));
                return new Connections(ledger, equipment);
            } catch (Exception exception) { ledger.close(); throw exception; }
        }
        Connection forTable(Table table) { return table.equipment() ? equipment : ledger; }
        List<Connection> all() { return ledger == equipment ? List.of(ledger) : List.of(ledger, equipment); }
        @Override public void close() throws SQLException {
            SQLException failure = null;
            for (Connection connection : all()) {
                try { connection.close(); } catch (SQLException exception) { if (failure == null) failure = exception; else failure.addSuppressed(exception); }
            }
            if (failure != null) throw failure;
        }
    }
}
