package cloud.mineplay.mobarena.economy;

import static org.junit.jupiter.api.Assertions.*;
import cloud.mineplay.mobarena.equipment.SnapshotStore;
import cloud.mineplay.mobarena.storage.DatabaseSettings;
import cloud.mineplay.mobarena.storage.SettlementStore;
import cloud.mineplay.mobarena.storage.StorageMigration;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** Runs only against an explicitly supplied disposable local MySQL server. */
@EnabledIfEnvironmentVariable(named = "MOB_ARENA_MYSQL_TEST_PORT", matches = "[0-9]+")
class MySqlStorageIntegrationTest {
    @TempDir Path directory;

    @Test void mysqlSchemaReservationsViewsAndBidirectionalMigrationPreserveData() throws Exception {
        String database = "mobarena_test_" + UUID.randomUUID().toString().replace("-", "");
        int port = Integer.parseInt(System.getenv("MOB_ARENA_MYSQL_TEST_PORT"));
        String username = System.getenv().getOrDefault("MOB_ARENA_MYSQL_TEST_USER", "root");
        String password = System.getenv().getOrDefault("MOB_ARENA_MYSQL_TEST_PASSWORD", "");
        Properties properties = new Properties();
        properties.setProperty("user", username); properties.setProperty("password", password);
        properties.setProperty("sslMode", "DISABLED"); properties.setProperty("allowPublicKeyRetrieval", "true");
        properties.setProperty("connectTimeout", "5000");
        try (Connection admin = DriverManager.getConnection("jdbc:mysql://127.0.0.1:" + port + "/", properties)) {
            try (var statement = admin.createStatement()) { statement.execute("CREATE DATABASE " + database); }
            try {
                var sqlite = DatabaseSettings.sqlite(directory.resolve("source"));
                var mysql = new DatabaseSettings(DatabaseSettings.Type.MYSQL, directory.resolve("remote-local"), "127.0.0.1", port, database, username, password, false);
                var returned = DatabaseSettings.sqlite(directory.resolve("returned"));
                Instant start = Instant.parse("2026-10-16T12:00:00Z");
                UUID player = UUID.randomUUID();
                UUID session = UUID.randomUUID();
                UUID token = UUID.randomUUID();
                try (var ledger = new SettlementStore(sqlite); var equipment = new SnapshotStore(sqlite)) {
                    ledger.prepare(new SessionResult(session, "roof", start, start.plusSeconds(120), 3, new BigDecimal("123.45"),
                            List.of(new SessionResult.Participant(player, "MySqlPlayer", 10, 25.5, 120)), false, "ELIMINATED"), EconomySettings.defaults(), start.plusSeconds(120));
                    var payment = ledger.claim(ledger.pending(1).getFirst().settlementId(), EconomySettings.defaults(), start.plusSeconds(121));
                    ledger.complete(payment.settlementId(), Settlement.Status.CONFIRMED, "", start.plusSeconds(122));
                    equipment.prepare(new SnapshotStore.Entry(player, token, "knight", new byte[]{0, 1, -1, 127}, null, SnapshotStore.Status.PREPARED));
                    equipment.beginRestore(player, token, new byte[]{2, 3, -128, 5});
                }
                var first = StorageMigration.migrate(sqlite, mysql, true);
                assertEquals(1, first.rows().get("sessions"));
                try (var ledger = new SettlementStore(mysql); var equipment = new SnapshotStore(mysql)) {
                    assertEquals("MySqlPlayer", ledger.match(session).participants().getFirst().playerName());
                    RewardCalculatorTest.assertMoney("123.45", ledger.stats(player).earned());
                    assertEquals(Settlement.Status.CONFIRMED, ledger.history(player, 1).getFirst().status());
                    assertArrayEquals(new byte[]{2, 3, -128, 5}, equipment.get(player).target());
                    equipment.confirm(player, token);
                    var later = new SessionResult(UUID.randomUUID(), "roof", start.plusSeconds(2000), start.plusSeconds(2120), 3,
                            new BigDecimal("60000"), List.of(new SessionResult.Participant(player, "MySqlPlayer", 20, 50, 120)), false);
                    ledger.prepare(later, EconomySettings.defaults(), start.plusSeconds(2120));
                    var payment = ledger.payouts(later.sessionId()).getFirst();
                    RewardCalculatorTest.assertMoney("25000", payment.intendedAmount());
                    ledger.claim(payment.settlementId(), EconomySettings.defaults(), start.plusSeconds(2121));
                    assertEquals(1, ledger.recover(start.plusSeconds(2122)));
                    assertEquals(Settlement.Status.UNCERTAIN, ledger.payouts(later.sessionId()).getFirst().status());
                }
                try (var connection = mysql.connect("mobarena.db"); var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT total_kills,confirmed_earned_minor FROM mobarena_leaderboard")) {
                    assertTrue(rows.next()); assertEquals(30, rows.getLong(1)); assertEquals(12345, rows.getLong(2));
                }
                var roundtrip = StorageMigration.migrate(mysql, returned, true);
                assertEquals(2, roundtrip.rows().get("sessions"));
                try (var from = new SettlementStore(mysql); var to = new SettlementStore(returned); var equipment = new SnapshotStore(returned)) {
                    assertEquals(from.history(player, 1), to.history(player, 1));
                    assertEquals(SnapshotStore.Status.CONFIRMED, equipment.get(player).status());
                    assertArrayEquals(new byte[]{0, 1, -1, 127}, equipment.get(player).original());
                }
            } finally {
                // The identifier is generated above, never supplied by the operator or application config.
                try (var statement = admin.createStatement()) { statement.execute("DROP DATABASE " + database); }
            }
        }
    }
}
