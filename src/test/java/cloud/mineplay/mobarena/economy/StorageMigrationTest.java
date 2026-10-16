package cloud.mineplay.mobarena.economy;

import static org.junit.jupiter.api.Assertions.*;
import cloud.mineplay.mobarena.equipment.SnapshotStore;
import cloud.mineplay.mobarena.storage.DatabaseSettings;
import cloud.mineplay.mobarena.storage.SettlementStore;
import cloud.mineplay.mobarena.storage.StorageMigration;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
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
}
