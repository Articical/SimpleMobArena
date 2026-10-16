package cloud.mineplay.mobarena.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Properties;
import org.bukkit.configuration.ConfigurationSection;

/** Shared connection settings for both settlement and equipment durability stores. */
public record DatabaseSettings(Type type, Path directory, String host, int port, String database,
        String username, String password, boolean useSsl) {
    public enum Type { SQLITE, MYSQL }

    public DatabaseSettings {
        if (type == null || directory == null) throw new IllegalArgumentException("Storage type and data directory are required");
        if (type == Type.MYSQL && (host == null || !host.matches("[A-Za-z0-9._:-]+") || port < 1 || port > 65535
                || database == null || !database.matches("[A-Za-z0-9_]+") || username == null || username.isBlank())) {
            throw new IllegalArgumentException("Invalid storage.mysql host, port, database, or username");
        }
    }

    public static DatabaseSettings sqlite(Path directory) {
        return new DatabaseSettings(Type.SQLITE, directory, "localhost", 3306, "mobarena", "", "", true);
    }

    public static DatabaseSettings load(ConfigurationSection storage, Path directory) {
        if (storage == null) return sqlite(directory);
        Type type;
        try { type = Type.valueOf(storage.getString("type", "SQLITE").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("storage.type must be SQLITE or MYSQL", exception); }
        if (type == Type.SQLITE) return sqlite(directory);
        String password = storage.getString("mysql.password", "");
        String environment = storage.getString("mysql.password-env", "");
        if (!environment.isBlank()) {
            password = System.getenv(environment);
            if (password == null) throw new IllegalArgumentException("Storage password environment variable is not set: " + environment);
        }
        Object portValue = storage.get("mysql.port", 3306);
        int port;
        try { port = new java.math.BigDecimal(String.valueOf(portValue)).intValueExact(); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("storage.mysql.port must be an integer", exception); }
        Object ssl = storage.get("mysql.use-ssl", true);
        if (!(ssl instanceof Boolean)) throw new IllegalArgumentException("storage.mysql.use-ssl must be true or false");
        return new DatabaseSettings(type, directory, storage.getString("mysql.host", "localhost"), port,
                storage.getString("mysql.database", "mobarena"), storage.getString("mysql.username", "mobarena"), password, (Boolean) ssl);
    }

    public boolean mysql() { return type == Type.MYSQL; }

    public Connection connect(String sqliteFile) throws SQLException, IOException {
        Connection connection = connectForMigration(sqliteFile);
        try {
            try (var tables = connection.getMetaData().getTables(connection.getCatalog(), null, "mobarena_migration_guard", new String[]{"TABLE"})) {
                if (tables.next()) {
                    try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT state FROM mobarena_migration_guard WHERE guard_id=1")) {
                        if (rows.next() && !"COMPLETE".equals(rows.getString(1))) {
                            throw new SQLException("Storage migration is incomplete. Preserve the source and use a fresh empty destination; do not enable this backend.");
                        }
                    }
                }
            }
            return connection;
        } catch (SQLException exception) { connection.close(); throw exception; }
    }

    /** Only the offline migrator may open a destination while its guard is RUNNING. */
    Connection connectForMigration(String sqliteFile) throws SQLException, IOException {
        Connection connection;
        try {
            if (mysql()) {
                Class.forName("com.mysql.cj.jdbc.Driver");
                Properties properties = new Properties();
                properties.setProperty("user", username);
                properties.setProperty("password", password);
                properties.setProperty("sslMode", useSsl ? "VERIFY_IDENTITY" : "DISABLED");
                properties.setProperty("connectionTimeZone", "UTC");
                properties.setProperty("connectTimeout", "5000");
                properties.setProperty("socketTimeout", "10000");
                properties.setProperty("allowPublicKeyRetrieval", "false");
                String jdbcHost = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
                connection = DriverManager.getConnection("jdbc:mysql://" + jdbcHost + ":" + port + "/" + database, properties);
                connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            } else {
                Files.createDirectories(directory.toAbsolutePath());
                Path file = directory.resolve(sqliteFile).toAbsolutePath().normalize();
                if (!file.getParent().equals(directory.toAbsolutePath().normalize())) throw new IllegalArgumentException("Invalid SQLite filename");
                Class.forName("org.sqlite.JDBC");
                connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                try (var statement = connection.createStatement()) {
                    statement.execute("PRAGMA foreign_keys=ON");
                    statement.execute("PRAGMA busy_timeout=5000");
                    statement.execute("PRAGMA journal_mode=WAL");
                    statement.execute("PRAGMA synchronous=FULL");
                }
            }
        } catch (ClassNotFoundException exception) { throw new SQLException("Required " + type + " driver is missing", exception); }
        return connection;
    }

    /** Never include credentials in loggable settings output. */
    @Override public String toString() {
        return mysql() ? "MYSQL " + host + ":" + port + "/" + database + " (credentials redacted)" : "SQLITE " + directory;
    }
}
