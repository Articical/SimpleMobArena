package cloud.mineplay.mobarena.equipment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotStoreTest {
    @TempDir Path directory;

    private SnapshotStore.Entry prepared(UUID player, byte... original) {
        return new SnapshotStore.Entry(player, UUID.randomUUID(), "knight", original, null,
                SnapshotStore.Status.PREPARED);
    }

    @Test void originalSurvivesProcessReopenBeforeAnyInventoryReplacement() throws Exception {
        UUID player = UUID.randomUUID();
        var original = prepared(player, (byte) 1, (byte) 2, (byte) 3);
        try (var store = new SnapshotStore(directory.resolve("equipment.db"))) { store.prepare(original); }
        try (var store = new SnapshotStore(directory.resolve("equipment.db"))) {
            assertEquals(1, store.outstanding().size());
            assertEquals(SnapshotStore.Status.PREPARED, store.get(player).status());
            assertArrayEquals(original.original(), store.get(player).original());
        }
    }

    @Test void duplicatePreparationCannotOverwriteThePlayersOriginalItems() throws Exception {
        UUID player = UUID.randomUUID();
        try (var store = new SnapshotStore(directory.resolve("equipment.db"))) {
            var original = prepared(player, (byte) 1);
            store.prepare(original);
            assertThrows(SQLException.class, () -> store.prepare(prepared(player, (byte) 99)));
            assertArrayEquals(new byte[] { 1 }, store.get(player).original());
            assertEquals(original.token(), store.get(player).token());
        }
    }

    @Test void crashDuringRestorationReplaysTheSameReplacementInsteadOfAddingItems() throws Exception {
        UUID player = UUID.randomUUID();
        var original = prepared(player, (byte) 4);
        byte[] exactReplacement = { 10, 20, 30 };
        try (var store = new SnapshotStore(directory.resolve("equipment.db"))) {
            store.prepare(original);
            store.beginRestore(player, original.token(), exactReplacement);
        }
        try (var store = new SnapshotStore(directory.resolve("equipment.db"))) {
            // The caller must replace inventory with this target while frozen, even if the
            // prior process already saved the replacement to Minecraft player data.
            var resumed = store.beginRestore(player, original.token(), new byte[] { 99 });
            assertEquals(SnapshotStore.Status.RESTORING, resumed.status());
            assertArrayEquals(exactReplacement, resumed.target());
            assertArrayEquals(new byte[] { 4 }, resumed.original());
        }
    }

    @Test void confirmedRestorationIsNotRecoveredOrReapplied() throws Exception {
        UUID player = UUID.randomUUID();
        var original = prepared(player, (byte) 1);
        try (var store = new SnapshotStore(directory.resolve("equipment.db"))) {
            store.prepare(original);
            store.beginRestore(player, original.token(), new byte[] { 1 });
            // Production calls confirm only after exact replacement and Player.saveData.
            store.confirm(player, original.token());
        }
        try (var store = new SnapshotStore(directory.resolve("equipment.db"))) {
            assertTrue(store.outstanding().isEmpty());
            assertThrows(SQLException.class, () -> store.beginRestore(player, original.token(), new byte[] { 1 }));
            assertThrows(SQLException.class, () -> store.confirm(player, original.token()));
        }
    }

    @Test void preparedSnapshotCannotBeConfirmedBeforeItsRestoreTargetExists() throws Exception {
        UUID player = UUID.randomUUID();
        var original = prepared(player, (byte) 1);
        try (var store = new SnapshotStore(directory.resolve("equipment.db"))) {
            store.prepare(original);
            assertThrows(SQLException.class, () -> store.confirm(player, original.token()));
            assertEquals(SnapshotStore.Status.PREPARED, store.get(player).status());
        }
    }

    @Test void newArenaVisitIsAllowedOnlyAfterPreviousRestorationWasConfirmed() throws Exception {
        UUID player = UUID.randomUUID();
        var first = prepared(player, (byte) 1);
        var next = prepared(player, (byte) 2);
        try (var store = new SnapshotStore(directory.resolve("equipment.db"))) {
            store.prepare(first);
            store.beginRestore(player, first.token(), new byte[] { 1 });
            assertThrows(SQLException.class, () -> store.prepare(next));
            store.confirm(player, first.token());
            store.prepare(next);
            assertEquals(next.token(), store.get(player).token());
            assertArrayEquals(new byte[] { 2 }, store.get(player).original());
        }
    }

    @Test void staleCompletionFromAnEarlierVisitCannotConfirmOrReplaceTheNextVisit() throws Exception {
        UUID player = UUID.randomUUID();
        var first = prepared(player, (byte) 1);
        var next = prepared(player, (byte) 2);
        try (var store = new SnapshotStore(directory.resolve("equipment.db"))) {
            store.prepare(first);
            store.beginRestore(player, first.token(), new byte[] { 1 });
            store.confirm(player, first.token());
            store.prepare(next);
            store.beginRestore(player, next.token(), new byte[] { 2 });
            assertThrows(SQLException.class, () -> store.confirm(player, first.token()));
            assertThrows(SQLException.class, () -> store.beginRestore(player, first.token(), new byte[] { 99 }));
            assertArrayEquals(new byte[] { 2 }, store.get(player).target());
        }
    }

    @Test void independentPlayersRetainIndependentRecoveryRecords() throws Exception {
        var first = prepared(UUID.randomUUID(), (byte) 1);
        var second = prepared(UUID.randomUUID(), (byte) 2);
        try (var store = new SnapshotStore(directory.resolve("equipment.db"))) {
            store.prepare(first); store.prepare(second);
            store.beginRestore(first.playerId(), first.token(), new byte[] { 1 });
            store.confirm(first.playerId(), first.token());
            assertEquals(1, store.outstanding().size());
            assertEquals(second.playerId(), store.outstanding().getFirst().playerId());
        }
    }

    @Test void entryPayloadsCannotBeChangedAfterCapture() {
        byte[] bytes = { 1, 2 };
        var entry = prepared(UUID.randomUUID(), bytes);
        bytes[0] = 99;
        byte[] returned = entry.original();
        returned[1] = 99;
        assertArrayEquals(new byte[] { 1, 2 }, entry.original());
    }

    @Test void newerDatabaseSchemaIsNeverSilentlyDowngraded() throws Exception {
        Path database = directory.resolve("equipment.db");
        try (var ignored = new SnapshotStore(database)) { }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) { statement.execute("PRAGMA user_version=2"); }
        assertThrows(SQLException.class, () -> new SnapshotStore(database));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(result.next());
            assertEquals(2, result.getInt(1));
        }
    }
}
