package cloud.mineplay.mobarena.economy;

import cloud.mineplay.mobarena.duels.DuelResult;
import cloud.mineplay.mobarena.storage.DatabaseSettings;
import cloud.mineplay.mobarena.storage.SettlementStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class DuelStoreTest {
    @TempDir Path directory;
    static final UUID RED = new UUID(0, 1), BLUE = new UUID(0, 2);
    static final Instant START = Instant.parse("2026-10-21T12:00:00Z");

    @Test void recordsBothTeamsWinnerNamesKillsDamageAndExitStateAcrossReopen() throws Exception {
        DuelResult result = result(new UUID(0, 10), START.plusSeconds(120));
        try (var store = new SettlementStore(directory.resolve("mobarena.db"))) {
            assertTrue(store.recordDuel(result));
        }
        try (var reopened = new SettlementStore(directory.resolve("mobarena.db"))) {
            assertEquals(result, reopened.duelMatch(result.sessionId()));
            assertEquals(List.of(result), reopened.duelHistory(RED, 1));
            assertEquals(List.of(result), reopened.duelHistory(BLUE, 1));
            assertNull(reopened.duelMatch(UUID.randomUUID()));
            assertTrue(reopened.duelHistory(UUID.randomUUID(), 1).isEmpty());
        }
    }

    @Test void replayIsIdempotentButConflictingSessionIsRejectedWithoutOverwriting() throws Exception {
        DuelResult original = result(UUID.randomUUID(), START.plusSeconds(120));
        var reordered = new ArrayList<>(original.participants());
        Collections.reverse(reordered);
        DuelResult replay = new DuelResult(original.sessionId(), original.arenaId(), original.mode(), original.startedAt(),
                original.finishedAt(), original.winner(), false, original.endReason(), reordered);
        DuelResult conflict = new DuelResult(original.sessionId(), original.arenaId(), original.mode(), original.startedAt(),
                original.finishedAt(), "BLUE", false, original.endReason(), original.participants());
        try (var store = new SettlementStore(directory.resolve("mobarena.db"))) {
            assertTrue(store.recordDuel(original));
            assertFalse(store.recordDuel(replay));
            assertThrows(SQLException.class, () -> store.recordDuel(conflict));
            assertEquals(original, store.duelMatch(original.sessionId()));
            assertEquals(1, store.duelHistory(RED, 1).size());
        }
    }

    @Test void duelKillsNeverBecomeMobKillsPaymentsOrWebsiteV1Events() throws Exception {
        var result = result(UUID.randomUUID(), START.plusSeconds(120));
        try (var store = new SettlementStore(directory.resolve("mobarena.db"))) {
            store.configureWebsiteExport(true, UUID.randomUUID(), START);
            var pve = new SessionResult(UUID.randomUUID(), "mobs", START, START.plusSeconds(90), 3, new BigDecimal("100.00"),
                    List.of(new SessionResult.Participant(RED, "MobPlayer", 50, 100, 90)), false);
            store.prepare(pve, EconomySettings.defaults(), pve.finishedAt());
            var before = store.stats(RED);
            var events = store.websiteEvents(START.plusSeconds(200), 25);
            store.recordDuel(result, START.plusSeconds(120));
            assertEquals(before, store.stats(RED));
            assertEquals(0, store.stats(BLUE).kills());
            assertEquals(1, store.top(10).size());
            assertEquals(1, store.history(RED, 1).size());
            assertTrue(store.payouts(result.sessionId()).isEmpty());
            assertNull(store.match(result.sessionId()));
            var after = store.websiteEvents(START.plusSeconds(200), 25).stream()
                    .filter(event -> event.kind() == cloud.mineplay.mobarena.storage.WebsiteExportStore.Kind.MATCH).toList();
            assertEquals(events.size(), after.size());
            assertEquals(events.getFirst().eventId(), after.getFirst().eventId());
            assertArrayEquals(events.getFirst().payload(), after.getFirst().payload());
            store.websiteStartBackfill(START.plusSeconds(201));
            store.websiteBackfillBatch(25, START.plusSeconds(202));
            assertTrue(store.websiteEvents(START.plusSeconds(203), 25).stream()
                    .filter(event -> event.kind() == cloud.mineplay.mobarena.storage.WebsiteExportStore.Kind.MATCH)
                    .allMatch(event -> event.sessionId().equals(pve.sessionId())));
        }
    }

    @Test void historyPaginatesInActualTimestampOrderIncludingFractionalSeconds() throws Exception {
        var expected = new ArrayList<DuelResult>();
        try (var store = new SettlementStore(directory.resolve("mobarena.db"))) {
            for (int index = 0; index < 12; index++) {
                var result = result(new UUID(0, index + 10), START.plusSeconds(120).plusNanos(index));
                expected.add(result);
                store.recordDuel(result);
            }
            Collections.reverse(expected);
            assertEquals(expected.subList(0, 10), store.duelHistory(RED, 1));
            assertEquals(expected.subList(10, 12), store.duelHistory(RED, 2));
            assertTrue(store.duelHistory(RED, 3).isEmpty());
            assertTrue(store.duelHistory(RED, Integer.MAX_VALUE).isEmpty());
            assertEquals(expected.subList(0, 10), store.duelHistory(RED, 0));
        }
    }

    @Test void historyTiesHaveStableSessionOrdering() throws Exception {
        var first = result(new UUID(0, 10), START.plusSeconds(120));
        var second = result(new UUID(0, 11), START.plusSeconds(120));
        try (var store = new SettlementStore(directory.resolve("mobarena.db"))) {
            store.recordDuel(second); store.recordDuel(first);
            assertEquals(List.of(second, first), store.duelHistory(RED, 1));
        }
    }

    @Test void cancelledMatchWithNoRosterIsRecordedWithoutInventingAParticipant() throws Exception {
        var result = new DuelResult(UUID.randomUUID(), "empty", "TEAM_DUEL", START, START, "DRAW", true,
                "ADMIN_CANCELLED", List.of());
        try (var store = new SettlementStore(directory.resolve("mobarena.db"))) {
            store.recordDuel(result);
            assertEquals(result, store.duelMatch(result.sessionId()));
            assertTrue(store.duelHistory(RED, 1).isEmpty());
        }
    }

    @Test void participantFailureRollsBackHeaderAndEntireRoster() throws Exception {
        var database = DatabaseSettings.sqlite(directory);
        var result = result(UUID.randomUUID(), START.plusSeconds(120));
        try (var store = new SettlementStore(database); var connection = database.connect("mobarena.db");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER fail_duel BEFORE INSERT ON duel_participants WHEN NEW.team='RED' BEGIN SELECT RAISE(ABORT,'injected failure'); END");
            assertThrows(SQLException.class, () -> store.recordDuel(result));
            assertNull(store.duelMatch(result.sessionId()));
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM duel_participants")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
            statement.execute("DROP TRIGGER fail_duel");
            assertTrue(store.recordDuel(result));
        }
    }

    @Test void upgradesSchemaFourWithoutChangingPveRowsOrExportIdentity() throws Exception {
        var database = DatabaseSettings.sqlite(directory);
        UUID sourceId = UUID.randomUUID();
        var pve = new SessionResult(UUID.randomUUID(), "mobs", START, START.plusSeconds(90), 3, new BigDecimal("100.00"),
                List.of(new SessionResult.Participant(RED, "MobPlayer", 50, 100, 90)), false);
        MatchDetails original;
        try (var store = new SettlementStore(database); var connection = database.connect("mobarena.db");
             var statement = connection.createStatement()) {
            store.configureWebsiteExport(true, sourceId, START);
            store.prepare(pve, EconomySettings.defaults(), pve.finishedAt());
            original = store.match(pve.sessionId());
            statement.execute("DROP TABLE duel_participants");
            statement.execute("DROP TABLE duels");
            statement.execute("PRAGMA user_version=4");
        }
        try (var store = new SettlementStore(database); var connection = database.connect("mobarena.db");
             var statement = connection.createStatement()) {
            assertEquals(original, store.match(pve.sessionId()));
            store.configureWebsiteExport(true, sourceId, START);
            assertTrue(store.recordDuel(result(UUID.randomUUID(), START.plusSeconds(120))));
            try (var rows = statement.executeQuery("PRAGMA user_version")) {
                assertTrue(rows.next()); assertEquals(5, rows.getInt(1));
            }
        }
    }

    static DuelResult result(UUID sessionId, Instant finishedAt) {
        return new DuelResult(sessionId, "courtyard", "TEAM_DUEL", START, finishedAt, "RED", false, "TEAM_ELIMINATED",
                List.of(new DuelResult.Participant(RED, "RedPlayer", "RED", 2, 123.45678901234567, 120, false),
                        new DuelResult.Participant(BLUE, ".Blue 玩家", "BLUE", 1, 20.5, 90, true)));
    }
}
