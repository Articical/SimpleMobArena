package cloud.mineplay.mobarena.equipment;

import cloud.mineplay.mobarena.storage.DatabaseSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Durable, fenced replacement snapshots. No Bukkit objects enter this store. */
public final class SnapshotStore implements AutoCloseable {
    public enum Status { PREPARED, RESTORING, CONFIRMED }

    public record Entry(UUID playerId, UUID token, String choice, byte[] original, byte[] target,
                        Status status) {
        public Entry {
            original = original.clone();
            target = target == null ? null : target.clone();
        }
        @Override public byte[] original() { return original.clone(); }
        @Override public byte[] target() { return target == null ? null : target.clone(); }
    }

    private final Connection connection;

    public SnapshotStore(Path file) throws Exception {
        this(openSqlite(file), false);
    }

    public SnapshotStore(DatabaseSettings settings) throws Exception {
        this(settings.connect("equipment.db"), settings.mysql());
    }

    private static Connection openSqlite(Path file) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
    }

    private SnapshotStore(Connection connection, boolean mysql) throws Exception {
        this.connection = connection;
        try (Statement statement = connection.createStatement()) {
            if (mysql) {
                statement.execute("CREATE TABLE IF NOT EXISTS mobarena_schema (component VARCHAR(32) PRIMARY KEY, version INTEGER NOT NULL) ENGINE=InnoDB");
                try (ResultSet version = statement.executeQuery("SELECT version FROM mobarena_schema WHERE component='equipment'")) {
                    if (version.next() && version.getInt(1) > 1) throw new SQLException("Equipment database schema is newer than this plugin; refusing downgrade");
                }
            } else {
                try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
                    if (version.next() && version.getInt(1) > 1) {
                        throw new SQLException("Equipment database schema is newer than this plugin; refusing downgrade");
                    }
                }
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("PRAGMA busy_timeout=5000");
            }
            String schema = """
                CREATE TABLE IF NOT EXISTS equipment_snapshots (
                    player_uuid VARCHAR(36) PRIMARY KEY,
                    token VARCHAR(36) NOT NULL UNIQUE,
                    choice VARCHAR(64) NOT NULL,
                    original BLOB NOT NULL,
                    restore_target BLOB,
                    status VARCHAR(16) NOT NULL CHECK(status IN ('PREPARED','RESTORING','CONFIRMED')),
                    created_at VARCHAR(40) NOT NULL,
                    updated_at VARCHAR(40) NOT NULL,
                    CHECK(status != 'RESTORING' OR restore_target IS NOT NULL)
                )
                """;
            statement.execute(mysql ? schema.replace(" BLOB", " LONGBLOB") + " ENGINE=InnoDB" : schema);
            if (mysql) statement.execute("INSERT INTO mobarena_schema(component,version) VALUES('equipment',1) ON DUPLICATE KEY UPDATE version=1");
            else statement.execute("PRAGMA user_version=1");
        } catch (Exception exception) {
            connection.close();
            throw exception;
        }
    }

    /** Refuses to replace an outstanding original, even after a duplicate join request. */
    public synchronized void prepare(Entry entry) throws SQLException {
        if (entry.status() != Status.PREPARED || entry.target() != null) {
            throw new IllegalArgumentException("New snapshots must be PREPARED without a restore target");
        }
        connection.setAutoCommit(false);
        try {
            String now = Instant.now().toString();
            int updated;
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE equipment_snapshots SET token=?,choice=?,original=?,restore_target=NULL,
                    status='PREPARED',created_at=?,updated_at=? WHERE player_uuid=? AND status='CONFIRMED'
                """)) {
                statement.setString(1, entry.token().toString());
                statement.setString(2, entry.choice());
                statement.setBytes(3, entry.original());
                statement.setString(4, now);
                statement.setString(5, now);
                statement.setString(6, entry.playerId().toString());
                updated = statement.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO equipment_snapshots
                        (player_uuid,token,choice,original,restore_target,status,created_at,updated_at)
                    VALUES (?,?,?,?,NULL,'PREPARED',?,?)
                    """)) {
                    statement.setString(1, entry.playerId().toString());
                    statement.setString(2, entry.token().toString());
                    statement.setString(3, entry.choice());
                    statement.setBytes(4, entry.original());
                    statement.setString(5, now);
                    statement.setString(6, now);
                    statement.executeUpdate();
                }
            }
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /** The exact replacement is durable before applying it. Replays cannot change the target. */
    public synchronized Entry beginRestore(UUID player, UUID token, byte[] target) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE equipment_snapshots SET restore_target=?,status='RESTORING',updated_at=?
            WHERE player_uuid=? AND token=? AND status='PREPARED'
            """)) {
            statement.setBytes(1, target);
            statement.setString(2, Instant.now().toString());
            statement.setString(3, player.toString());
            statement.setString(4, token.toString());
            statement.executeUpdate();
        }
        Entry entry = get(player);
        if (entry == null || !entry.token().equals(token) || entry.status() != Status.RESTORING) {
            throw new SQLException("Snapshot restore is no longer pending for this token");
        }
        return entry;
    }

    /** Called only AFTER replacement and Player.saveData() while the player is still frozen. */
    public synchronized void confirm(UUID player, UUID token) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE equipment_snapshots SET status='CONFIRMED',updated_at=?
            WHERE player_uuid=? AND token=? AND status='RESTORING'
            """)) {
            statement.setString(1, Instant.now().toString());
            statement.setString(2, player.toString());
            statement.setString(3, token.toString());
            if (statement.executeUpdate() != 1) throw new SQLException("Restore confirmation was stale or duplicated");
        }
    }

    public synchronized Entry get(UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM equipment_snapshots WHERE player_uuid=?")) {
            statement.setString(1, player.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(result) : null;
            }
        }
    }

    public synchronized List<Entry> outstanding() throws SQLException {
        List<Entry> entries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM equipment_snapshots WHERE status != 'CONFIRMED' ORDER BY player_uuid");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) entries.add(read(result));
        }
        return List.copyOf(entries);
    }

    private static Entry read(ResultSet result) throws SQLException {
        return new Entry(UUID.fromString(result.getString("player_uuid")),
                UUID.fromString(result.getString("token")), result.getString("choice"),
                result.getBytes("original"), result.getBytes("restore_target"),
                Status.valueOf(result.getString("status")));
    }

    @Override public synchronized void close() throws SQLException { connection.close(); }
}
