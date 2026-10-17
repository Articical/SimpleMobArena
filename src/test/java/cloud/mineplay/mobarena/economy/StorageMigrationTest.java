package cloud.mineplay.mobarena.economy;

import static org.junit.jupiter.api.Assertions.*;
import cloud.mineplay.mobarena.equipment.SnapshotStore;
import cloud.mineplay.mobarena.storage.DatabaseSettings;
import cloud.mineplay.mobarena.storage.SettlementStore;
import cloud.mineplay.mobarena.storage.StorageMigration;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageMigrationTest {
    @TempDir Path directory;
    private static final UUID PLAYER = new UUID(0, 42);
    private static final Instant START = Instant.parse("2026-10-16T12:00:00Z");

    @Test void copiesEveryLedgerRecordAndRecoveryBlobWithVerifiedReadback() throws Exception {
        var source = DatabaseSettings.sqlite(directory.resolve("source"));
        var target = DatabaseSettings.sqlite(directory.resolve("target"));
        UUID session = populate(source);
        var report = StorageMigration.migrate(source, target, true);
        assertEquals(1, report.rows().get("sessions"));
        assertEquals(1, report.rows().get("participants"));
        assertEquals(1, report.rows().get("settlements"));
        assertEquals(1, report.rows().get("equipment_snapshots"));
        assertEquals(64, report.sha256().get("equipment_snapshots").length());
        try (var from = new SettlementStore(source); var to = new SettlementStore(target);
             var originalSnapshots = new SnapshotStore(source); var copiedSnapshots = new SnapshotStore(target)) {
            assertEquals(from.payouts(session), to.payouts(session));
            assertEquals(from.match(session), to.match(session));
            assertArrayEquals(originalSnapshots.get(PLAYER).original(), copiedSnapshots.get(PLAYER).original());
            assertArrayEquals(originalSnapshots.get(PLAYER).target(), copiedSnapshots.get(PLAYER).target());
            assertEquals(SnapshotStore.Status.RESTORING, copiedSnapshots.get(PLAYER).status());
            assertEquals(Settlement.Status.PROCESSING, to.payouts(session).getFirst().status());
            assertEquals(1, to.recover(START.plusSeconds(200)));
            assertEquals(Settlement.Status.PROCESSING, from.payouts(session).getFirst().status());
        }
    }

    @Test void refusesSameDatabaseOrPopulatedTargetWithoutOverwriting() throws Exception {
        var source = DatabaseSettings.sqlite(directory.resolve("source"));
        var target = DatabaseSettings.sqlite(directory.resolve("target"));
        populate(source); populate(target);
        assertThrows(IllegalArgumentException.class, () -> StorageMigration.migrate(source, source, true));
        assertThrows(SQLException.class, () -> StorageMigration.migrate(source, target, true));
        try (var store = new SettlementStore(target)) { assertEquals(1, store.stats(PLAYER).sessions()); }
        assertThrows(IllegalArgumentException.class, () -> StorageMigration.migrate(source, target, false));
    }

    @Test void exportIdentityRevisionsExactBytesAndDeliveryMetadataSurviveRoundtrip() throws Exception {
        var source = DatabaseSettings.sqlite(directory.resolve("source"));
        var target = DatabaseSettings.sqlite(directory.resolve("target"));
        var returned = DatabaseSettings.sqlite(directory.resolve("returned"));
        UUID session = populate(source);
        populateExportQueue(source, session);
        var before = exportData(source);

        var first = StorageMigration.migrate(source, target, true);
        assertEquals(1, first.rows().get("website_export_state"));
        assertEquals(1, first.rows().get("website_match_revisions"));
        assertEquals(3, first.rows().get("website_export_events"));
        assertEquals(64, first.sha256().get("website_export_events").length());
        assertEquals(before, exportData(source), "Migration must not change the original queue or lineage");
        assertEquals(before, exportData(target));

        var second = StorageMigration.migrate(target, returned, true);
        assertEquals(first.rows(), second.rows());
        assertEquals(first.sha256(), second.sha256());
        assertEquals(before, exportData(returned));
    }

    @Test void floatingDamageFingerprintUsesExactStoredDoubleAndMigrationPreservesItsBits() throws Exception {
        var source = DatabaseSettings.sqlite(directory.resolve("source"));
        var target = DatabaseSettings.sqlite(directory.resolve("target"));
        UUID session = new UUID(0, 99);
        double damage = 123.45678901234567;
        try (var ledger = new SettlementStore(source); var snapshots = new SnapshotStore(source)) {
            ledger.prepare(new SessionResult(session, "spawn", START, START.plusSeconds(120), 3,
                    new BigDecimal("123.45"), List.of(new SessionResult.Participant(PLAYER, "Precision", 10, damage, 120)),
                    false, "ELIMINATED"), EconomySettings.defaults(), START.plusSeconds(120));
        }
        var report = StorageMigration.migrate(source, target, true);
        // Known participant fixture encoded with the round-trip double, not SQLite's rounded CAST-to-text value.
        assertEquals("9fc5f2db362dfc651dd173466df5b9351b962945964e1de0913b993320a9783e", report.sha256().get("participants"));
        try (var from = new SettlementStore(source); var to = new SettlementStore(target)) {
            assertEquals(Double.doubleToRawLongBits(damage), Double.doubleToRawLongBits(from.match(session).participants().getFirst().damage()));
            assertEquals(Double.doubleToRawLongBits(damage), Double.doubleToRawLongBits(to.match(session).participants().getFirst().damage()));
        }
    }

    @Test void existingSourceIdentityMakesDestinationNonemptyEvenWithoutMatches() throws Exception {
        var source = DatabaseSettings.sqlite(directory.resolve("source"));
        var target = DatabaseSettings.sqlite(directory.resolve("target"));
        populate(source);
        try (var ledger = new SettlementStore(target); var snapshots = new SnapshotStore(target);
             var connection = target.connect("mobarena.db");
             var insert = connection.prepareStatement("INSERT INTO website_export_state(id,source_id,backfill_active) VALUES(1,?,0)")) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.executeUpdate();
        }
        var before = exportData(target);
        SQLException failure = assertThrows(SQLException.class, () -> StorageMigration.migrate(source, target, true));
        assertTrue(failure.getMessage().contains("website_export_state"));
        assertEquals(before, exportData(target));
    }

    @Test void oldSourceSchemaIsRejectedWithoutUpgradingOrChangingItsData() throws Exception {
        var source = DatabaseSettings.sqlite(directory.resolve("source"));
        var target = DatabaseSettings.sqlite(directory.resolve("target"));
        UUID session = populate(source);
        try (var connection = source.connect("mobarena.db"); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE website_export_events");
            statement.execute("DROP TABLE website_match_revisions");
            statement.execute("DROP TABLE website_export_state");
            statement.execute("PRAGMA user_version=3");
        }
        SQLException failure = assertThrows(SQLException.class, () -> StorageMigration.migrate(source, target, true));
        assertTrue(failure.getMessage().contains("website export disabled"));
        try (var connection = source.connect("mobarena.db"); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA user_version")) { assertTrue(rows.next()); assertEquals(3, rows.getInt(1)); }
            try (var rows = statement.executeQuery("SELECT session_id FROM sessions")) { assertTrue(rows.next()); assertEquals(session.toString(), rows.getString(1)); }
            try (var rows = connection.getMetaData().getTables(null, null, "website_export_state", new String[]{"TABLE"})) { assertFalse(rows.next()); }
        }
    }

    @Test void partialCopyFailureLeavesTargetGuardedAndSourceIntact() throws Exception {
        var source = DatabaseSettings.sqlite(directory.resolve("source"));
        var target = DatabaseSettings.sqlite(directory.resolve("target"));
        UUID session = populate(source);
        try (var ledger = new SettlementStore(target); var snapshots = new SnapshotStore(target);
             var connection = target.connect("equipment.db"); var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER fail_copy BEFORE INSERT ON equipment_snapshots BEGIN SELECT RAISE(ABORT,'injected copy failure'); END");
        }
        assertThrows(SQLException.class, () -> StorageMigration.migrate(source, target, true));
        assertThrows(SQLException.class, () -> target.connect("mobarena.db"));
        assertThrows(SQLException.class, () -> target.connect("equipment.db"));
        try (var ledger = new SettlementStore(source); var snapshots = new SnapshotStore(source)) {
            assertEquals(Settlement.Status.PROCESSING, ledger.payouts(session).getFirst().status());
            assertArrayEquals(new byte[]{0, 1, 2, -1}, snapshots.get(PLAYER).original());
        }
    }

    @Test void settingsValidateBackendAndNeverRenderPassword() {
        var config = new YamlConfiguration();
        config.set("type", "MYSQL"); config.set("mysql.password", "should-not-appear");
        var settings = DatabaseSettings.load(config, directory);
        assertTrue(settings.mysql()); assertTrue(settings.useSsl());
        assertFalse(settings.toString().contains("should-not-appear"));
        config.set("mysql.host", "host/db?unsafe");
        assertThrows(IllegalArgumentException.class, () -> DatabaseSettings.load(config, directory));
        config.set("mysql.host", "localhost"); config.set("mysql.port", 3306.5);
        assertThrows(IllegalArgumentException.class, () -> DatabaseSettings.load(config, directory));
    }

    private UUID populate(DatabaseSettings settings) throws Exception {
        UUID session = UUID.randomUUID();
        try (var store = new SettlementStore(settings); var snapshots = new SnapshotStore(settings)) {
            var result = new SessionResult(session, "spawn", START, START.plusSeconds(120), 3,
                    new BigDecimal("123.45"), List.of(new SessionResult.Participant(PLAYER, "Alex", 10, 42.5, 120)), false, "ELIMINATED");
            store.prepare(result, EconomySettings.defaults(), START.plusSeconds(120));
            store.claim(store.pending(1).getFirst().settlementId(), EconomySettings.defaults(), START.plusSeconds(121));
            UUID token = UUID.randomUUID();
            snapshots.prepare(new SnapshotStore.Entry(PLAYER, token, "knight", new byte[]{0, 1, 2, -1}, null, SnapshotStore.Status.PREPARED));
            snapshots.beginRestore(PLAYER, token, new byte[]{4, 5, 6, -1});
        }
        return session;
    }

    /** Deliberately includes acknowledged, retrying and quarantined rows, including a null invalid payload. */
    static void populateExportQueue(DatabaseSettings settings, UUID session) throws Exception {
        List<UUID> events = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        try (var connection = settings.connect("mobarena.db")) {
            connection.setAutoCommit(false);
            try (var state = connection.prepareStatement("INSERT INTO website_export_state(id,source_id,backfill_active,backfill_cursor,last_acknowledged_at,last_failure,last_failure_event_id) VALUES(1,?,1,?,?,?,?)")) {
                state.setString(1, UUID.randomUUID().toString());
                state.setString(2, session.toString());
                state.setString(3, "2026-10-16T12:02:03.123456789Z");
                state.setString(4, "HTTP_401");
                state.setString(5, events.get(2).toString());
                state.executeUpdate();
            }
            try (var revision = connection.prepareStatement("INSERT INTO website_match_revisions(session_id,revision) VALUES(?,3)")) {
                revision.setString(1, session.toString());
                revision.executeUpdate();
            }
            try (var event = connection.prepareStatement("INSERT INTO website_export_events(event_id,session_id,revision,payload,created_at,attempts,next_attempt_at,delivery_status,failure_code,acknowledged_at,stored_revision) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                for (int revision = 1; revision <= 3; revision++) {
                    event.setString(1, events.get(revision - 1).toString());
                    event.setString(2, session.toString());
                    event.setLong(3, revision);
                    event.setBytes(4, revision == 3 ? null : ("{\"name\":\".Bedrock 玩家\",\"revision\":\"" + revision + "\",\"money\":\"123.45\"}\n").getBytes(StandardCharsets.UTF_8));
                    event.setString(5, "2026-10-16T12:02:00.123456789Z");
                    event.setInt(6, revision == 2 ? 7 : 1);
                    event.setString(7, "2026-10-16T12:03:04.123456789Z");
                    event.setString(8, revision == 1 ? "DELIVERED" : revision == 2 ? "PENDING" : "QUARANTINED");
                    event.setString(9, revision == 1 ? null : revision == 2 ? "HTTP_503" : "INVALID_SNAPSHOT");
                    event.setString(10, revision == 1 ? "2026-10-16T12:02:03.123456789Z" : null);
                    if (revision == 1) event.setLong(11, 2); else event.setNull(11, java.sql.Types.BIGINT);
                    event.executeUpdate();
                }
            }
            connection.commit();
        }
    }

    static Map<String, List<List<String>>> exportData(DatabaseSettings settings) throws Exception {
        var result = new LinkedHashMap<String, List<List<String>>>();
        try (var connection = settings.connect("mobarena.db"); var statement = connection.createStatement()) {
            for (String table : List.of("website_export_state", "website_match_revisions", "website_export_events")) {
                var values = new ArrayList<List<String>>();
                try (var rows = statement.executeQuery("SELECT * FROM " + table + " ORDER BY 1")) {
                    while (rows.next()) {
                        var row = new ArrayList<String>();
                        for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                            if (rows.getObject(column) == null) row.add(null);
                            else if (rows.getMetaData().getColumnName(column).equals("payload")) row.add(Base64.getEncoder().encodeToString(rows.getBytes(column)));
                            else row.add(rows.getString(column));
                        }
                        values.add(row);
                    }
                }
                result.put(table, values);
            }
        }
        return result;
    }
}
