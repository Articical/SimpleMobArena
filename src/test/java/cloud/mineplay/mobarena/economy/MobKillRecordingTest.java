package cloud.mineplay.mobarena.economy;

import static org.junit.jupiter.api.Assertions.*;
import cloud.mineplay.mobarena.duels.DuelResult;
import cloud.mineplay.mobarena.storage.DatabaseSettings;
import cloud.mineplay.mobarena.storage.SettlementStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MobKillRecordingTest {
    @TempDir Path directory;
    private static final UUID PLAYER = new UUID(0, 1);
    private static final UUID OTHER = new UUID(0, 2);
    private static final Instant START = Instant.parse("2026-11-10T12:00:00Z");

    @Test void detachedContributionPreservesUnknownHistoryAndRejectsInvalidTypeCounts() {
        var mutable = new HashMap<>(Map.of("ZOMBIE", 6, "SKELETON", 4));
        var player = participant(PLAYER, 10, mutable);
        mutable.put("ZOMBIE", 100);
        assertEquals(Map.of("ZOMBIE", 6, "SKELETON", 4), player.mobKills());
        assertThrows(UnsupportedOperationException.class, () -> player.mobKills().put("GOAT", 1));
        assertTrue(new SessionResult.Participant(PLAYER, "Alex", 20, 0, 120).mobKills().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> participant(PLAYER, 10, Map.of("zombie", 1)));
        assertThrows(IllegalArgumentException.class, () -> participant(PLAYER, 10, Map.of("ZOMBIE", -1)));
        assertThrows(IllegalArgumentException.class, () -> participant(PLAYER, 10, Map.of("ZOMBIE", 8, "SKELETON", 3)));
        assertThrows(IllegalArgumentException.class, () -> participant(PLAYER, Integer.MAX_VALUE,
                Map.of("ZOMBIE", Integer.MAX_VALUE, "SKELETON", Integer.MAX_VALUE)));
    }

    @Test void committedTypesSurviveRestartAndReplayWithoutInventingLegacyKills() throws Exception {
        var settings = DatabaseSettings.sqlite(directory);
        var legacy = match(0, 3, false, new SessionResult.Participant(PLAYER, "Alex", 20, 0, 120));
        var typed = match(200, 5, false, participant(PLAYER, 10, Map.of("ZOMBIE", 6, "SKELETON", 4)),
                participant(OTHER, 3, Map.of("GOAT", 3)));
        var cancelled = match(400, 1, true, participant(PLAYER, 2, Map.of("ZOMBIE", 2)));
        try (var store = new SettlementStore(settings)) {
            assertTrue(store.prepare(legacy, EconomySettings.defaults(), legacy.finishedAt()));
            assertTrue(store.prepare(typed, EconomySettings.defaults(), typed.finishedAt()));
            assertTrue(store.prepare(cancelled, EconomySettings.defaults(), cancelled.finishedAt()));
        }
        try (var store = new SettlementStore(settings)) {
            assertFalse(store.prepare(typed, EconomySettings.defaults(), typed.finishedAt()));
            assertEquals(Map.of("ZOMBIE", 6, "SKELETON", 4), store.matchMobKills(typed.sessionId(), PLAYER));
            assertTrue(store.matchMobKills(legacy.sessionId(), PLAYER).isEmpty());
            assertEquals(Map.of("ZOMBIE", 8L, "SKELETON", 4L), store.mobKills(PLAYER));
            assertEquals(Map.of("GOAT", 3L), store.mobKills(OTHER));
            assertEquals(Map.of("total_kills", 32L, "matches_played", 3L, "highest_wave", 5L,
                    "duels_played", 0L, "duel_wins", 0L, "zombie_kills", 8L, "skeleton_kills", 4L),
                    store.achievementProgress(PLAYER).totals());
            assertEquals(32, store.stats(PLAYER).kills());
            assertEquals(1, store.payouts(cancelled.sessionId()).size());
        }
    }

    @Test void typeWriteFailureRollsBackMatchParticipantsAndPaymentsTogether() throws Exception {
        var settings = DatabaseSettings.sqlite(directory);
        var result = match(0, 3, false, participant(PLAYER, 10, Map.of("ZOMBIE", 6, "SKELETON", 4)));
        try (var store = new SettlementStore(settings)) {
            try (var connection = settings.connect("mobarena.db"); var statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER reject_type BEFORE INSERT ON participant_mob_kills WHEN NEW.mob_type='SKELETON' BEGIN SELECT RAISE(ABORT,'injected type failure'); END");
            }
            assertThrows(SQLException.class, () -> store.prepare(result, EconomySettings.defaults(), result.finishedAt()));
            assertNull(store.match(result.sessionId()));
            assertTrue(store.payouts(result.sessionId()).isEmpty());
            assertTrue(store.mobKills(PLAYER).isEmpty());
            assertTrue(store.achievementPlayers(null, 10).isEmpty());
            try (var connection = settings.connect("mobarena.db"); var statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER reject_type");
            }
            assertTrue(store.prepare(result, EconomySettings.defaults(), result.finishedAt()));
            assertEquals(Map.of("ZOMBIE", 6L, "SKELETON", 4L), store.mobKills(PLAYER));
        }
    }

    @Test void upgradingSchemaFivePreservesAggregateHistoryWithoutGuessingTypes() throws Exception {
        var settings = DatabaseSettings.sqlite(directory);
        var old = match(0, 3, false, new SessionResult.Participant(PLAYER, "Alex", 20, 0, 120));
        try (var store = new SettlementStore(settings)) {
            store.prepare(old, EconomySettings.defaults(), old.finishedAt());
        }
        try (var connection = settings.connect("mobarena.db"); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE participant_mob_kills");
            statement.execute("DROP TABLE live_mob_kills");
            statement.execute("PRAGMA user_version=5");
        }
        try (var store = new SettlementStore(settings)) {
            assertEquals(20, store.stats(PLAYER).kills());
            assertEquals(20L, store.achievementProgress(PLAYER).totals().get("total_kills"));
            assertTrue(store.mobKills(PLAYER).isEmpty());
            assertEquals(1, store.payouts(old.sessionId()).size());
        }
        try (var connection = settings.connect("mobarena.db"); var statement = connection.createStatement();
             var rows = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(rows.next()); assertEquals(6, rows.getInt(1));
        }
    }

    @Test void duelWinsExcludeCancelledGamesAndDoNotCountPlayerKillsAsMobKills() throws Exception {
        try (var store = new SettlementStore(DatabaseSettings.sqlite(directory))) {
            var pve = match(0, 3, false, participant(PLAYER, 2, Map.of("ZOMBIE", 2)));
            store.prepare(pve, EconomySettings.defaults(), pve.finishedAt());
            var win = duel(200, PLAYER, "Alex", "RED", false);
            assertTrue(store.recordDuel(win));
            assertFalse(store.recordDuel(win));
            store.recordDuel(duel(400, PLAYER, "Alex", "RED", true));
            store.recordDuel(duel(600, PLAYER, "Alex", "DRAW", false));
            store.recordDuel(duel(800, PLAYER, "NewAlex", "BLUE", false));
            var progress = store.achievementProgress(PLAYER);
            assertEquals("NewAlex", progress.playerName());
            assertEquals(Map.of("total_kills", 2L, "matches_played", 1L, "highest_wave", 3L,
                    "duels_played", 4L, "duel_wins", 1L, "zombie_kills", 2L), progress.totals());
            assertThrows(UnsupportedOperationException.class, () -> progress.totals().put("total_kills", 99L));
        }
    }

    @Test void playerReplayPagesAllHistoryOnceIncludingDuelOnlyPlayers() throws Exception {
        try (var store = new SettlementStore(DatabaseSettings.sqlite(directory))) {
            var result = match(0, 3, false, participant(PLAYER, 1, Map.of("ZOMBIE", 1)),
                    participant(OTHER, 1, Map.of("ZOMBIE", 1)));
            store.prepare(result, EconomySettings.defaults(), result.finishedAt());
            store.recordDuel(duel(200, PLAYER, "Alex", "RED", false));
            var third = new UUID(0, 3);
            store.recordDuel(duel(400, third, "DuelOnly", "RED", false));
            var paged = new ArrayList<UUID>();
            UUID cursor = null;
            for (int page = 0; page < 5; page++) {
                var players = store.achievementPlayers(cursor, 1);
                if (players.isEmpty()) break;
                paged.addAll(players); cursor = players.getLast();
            }
            assertEquals(List.of(PLAYER, OTHER, third), paged);
            assertEquals(List.of(PLAYER, OTHER, third), store.achievementPlayers(null, 100));
            assertEquals(0L, store.achievementProgress(third).totals().get("total_kills"));
            assertEquals(1L, store.achievementProgress(third).totals().get("duel_wins"));
            var unknown = new UUID(0, 99);
            assertEquals(unknown.toString(), store.achievementProgress(unknown).playerName());
            assertTrue(store.achievementProgress(unknown).totals().values().stream().allMatch(value -> value == 0));
        }
    }

    @Test void liveCheckpointsAreMonotonicAndSurviveRestartWithoutInventingMatches() throws Exception {
        var settings = DatabaseSettings.sqlite(directory);
        UUID session = UUID.randomUUID();
        try (var store = new SettlementStore(settings)) {
            var first = store.checkpointMobKills(session, PLAYER, "LiveAlex", Map.of("ZOMBIE", 2));
            assertEquals(2L, first.totals().get("total_kills"));
            assertEquals(0L, first.totals().get("matches_played"));
            assertEquals(0L, first.totals().get("highest_wave"));
            assertEquals("LiveAlex", first.playerName());
            store.checkpointMobKills(session, PLAYER, "LiveAlex", Map.of("ZOMBIE", 5, "SKELETON", 2));
            store.checkpointMobKills(session, PLAYER, "LiveAlex", Map.of("ZOMBIE", 3));
            assertEquals(7L, store.checkpointMobKills(session, PLAYER, "LiveAlex", Map.of("ZOMBIE", 5, "SKELETON", 2)).totals().get("total_kills"));
        }
        try (var store = new SettlementStore(settings)) {
            assertEquals(Map.of("ZOMBIE", 5L, "SKELETON", 2L), store.mobKills(PLAYER));
            assertEquals(7L, store.achievementProgress(PLAYER).totals().get("total_kills"));
            assertEquals(List.of(PLAYER), store.achievementPlayers(null, 100));
            assertNull(store.match(session));
            assertTrue(store.history(PLAYER, 1).isEmpty());
            assertTrue(store.payouts(session).isEmpty());
            assertTrue(store.matchMobKills(session, PLAYER).isEmpty());
            assertEquals(0, store.stats(PLAYER).kills());
        }
    }

    @Test void finalizationReplacesOnlyItsLiveRowsAndIgnoresLateCheckpointsEvenWhenCancelled() throws Exception {
        var settings = DatabaseSettings.sqlite(directory);
        var finished = match(0, 3, true, participant(PLAYER, 5, Map.of("ZOMBIE", 5)), participant(OTHER, 2, Map.of("GOAT", 2)));
        UUID running = UUID.randomUUID();
        try (var store = new SettlementStore(settings)) {
            store.checkpointMobKills(finished.sessionId(), PLAYER, "Alex", Map.of("ZOMBIE", 3));
            store.checkpointMobKills(finished.sessionId(), OTHER, "Other", Map.of("GOAT", 2));
            store.checkpointMobKills(running, PLAYER, "Alex", Map.of("SKELETON", 4));
            assertTrue(store.prepare(finished, EconomySettings.defaults(), finished.finishedAt()));
            assertFalse(store.prepare(finished, EconomySettings.defaults(), finished.finishedAt()));
            assertEquals(9L, store.achievementProgress(PLAYER).totals().get("total_kills"));
            assertEquals(1L, store.achievementProgress(PLAYER).totals().get("matches_played"));
            assertEquals(Map.of("ZOMBIE", 5L, "SKELETON", 4L), store.mobKills(PLAYER));
            assertEquals(2L, store.achievementProgress(OTHER).totals().get("total_kills"));
            assertEquals(9L, store.checkpointMobKills(finished.sessionId(), PLAYER, "Stale", Map.of("ZOMBIE", 99)).totals().get("total_kills"));
            assertEquals(Settlement.Status.WITHHELD, store.payouts(finished.sessionId()).getFirst().status());
            try (var connection = settings.connect("mobarena.db"); var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT session_id,kills FROM live_mob_kills")) {
                assertTrue(rows.next()); assertEquals(running.toString(), rows.getString(1)); assertEquals(4, rows.getInt(2));
                assertFalse(rows.next());
            }
        }
    }

    @Test void incompleteFinalResultCannotDiscardPreviouslyAwardedCheckpointEvidence() throws Exception {
        var settings = DatabaseSettings.sqlite(directory);
        var incomplete = match(0, 3, false, participant(PLAYER, 2, Map.of("ZOMBIE", 2)));
        try (var store = new SettlementStore(settings)) {
            store.checkpointMobKills(incomplete.sessionId(), PLAYER, "Alex", Map.of("ZOMBIE", 3));
            assertThrows(SQLException.class, () -> store.prepare(incomplete, EconomySettings.defaults(), incomplete.finishedAt()));
            assertNull(store.match(incomplete.sessionId()));
            assertEquals(3L, store.achievementProgress(PLAYER).totals().get("zombie_kills"));
            assertEquals(0L, store.achievementProgress(PLAYER).totals().get("matches_played"));
            var missingPlayer = new SessionResult(incomplete.sessionId(), "spawn", START, START.plusSeconds(120),
                    3, BigDecimal.ZERO, List.of(participant(OTHER, 4, Map.of("ZOMBIE", 4))), false);
            assertThrows(SQLException.class, () -> store.prepare(missingPlayer, EconomySettings.defaults(), missingPlayer.finishedAt()));
            var complete = new SessionResult(incomplete.sessionId(), "spawn", START, START.plusSeconds(120),
                    3, BigDecimal.ZERO, List.of(participant(PLAYER, 3, Map.of("ZOMBIE", 3))), false);
            assertTrue(store.prepare(complete, EconomySettings.defaults(), complete.finishedAt()));
            assertEquals(3L, store.achievementProgress(PLAYER).totals().get("total_kills"));
        }
    }

    @Test void failedCheckpointDoesNotPublishPartialSpeciesCounts() throws Exception {
        var settings = DatabaseSettings.sqlite(directory);
        try (var store = new SettlementStore(settings)) {
            UUID session = UUID.randomUUID();
            store.checkpointMobKills(session, PLAYER, "Alex", Map.of("ZOMBIE", 2));
            try (var connection = settings.connect("mobarena.db"); var statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER reject_live_type BEFORE INSERT ON live_mob_kills WHEN NEW.mob_type='SKELETON' BEGIN SELECT RAISE(ABORT,'injected live failure'); END");
            }
            assertThrows(SQLException.class, () -> store.checkpointMobKills(session, PLAYER, "Alex", Map.of("ZOMBIE", 5, "SKELETON", 2)));
            assertEquals(Map.of("ZOMBIE", 2L), store.mobKills(PLAYER));
            assertEquals(2L, store.achievementProgress(PLAYER).totals().get("total_kills"));
        }
    }

    @Test void concurrentPlayersAndOutOfOrderUpdatesRetainSeparateMaximumCounters() throws Exception {
        try (var store = new SettlementStore(DatabaseSettings.sqlite(directory)); var executor = Executors.newFixedThreadPool(4)) {
            UUID first = UUID.randomUUID(), second = UUID.randomUUID();
            var tasks = new ArrayList<java.util.concurrent.Callable<Void>>();
            for (int count = 12; count >= 1; count--) {
                int value = count;
                tasks.add(() -> { store.checkpointMobKills(first, PLAYER, "Alex", Map.of("ZOMBIE", value)); return null; });
                tasks.add(() -> { store.checkpointMobKills(first, OTHER, "Other", Map.of("GOAT", value)); return null; });
                tasks.add(() -> { store.checkpointMobKills(second, PLAYER, "Alex", Map.of("SKELETON", value)); return null; });
            }
            for (var completed : executor.invokeAll(tasks)) completed.get();
            assertEquals(Map.of("ZOMBIE", 12L, "SKELETON", 12L), store.mobKills(PLAYER));
            assertEquals(Map.of("GOAT", 12L), store.mobKills(OTHER));
            assertEquals(24L, store.achievementProgress(PLAYER).totals().get("total_kills"));
            assertEquals(List.of(PLAYER, OTHER), store.achievementPlayers(null, 100));
        }
    }

    private static SessionResult.Participant participant(UUID player, int kills, Map<String, Integer> types) {
        return new SessionResult.Participant(player, "Alex", kills, 42, 120, types);
    }

    private static SessionResult match(long offset, int waves, boolean cancelled, SessionResult.Participant... players) {
        return new SessionResult(UUID.randomUUID(), "spawn", START.plusSeconds(offset), START.plusSeconds(offset + 120),
                waves, new BigDecimal("100"), List.of(players), cancelled, cancelled ? "CANCELLED" : "ELIMINATED");
    }

    private static DuelResult duel(long offset, UUID player, String name, String winner, boolean cancelled) {
        return new DuelResult(UUID.randomUUID(), "duel", "DUEL", START.plusSeconds(offset), START.plusSeconds(offset + 120),
                winner, cancelled, cancelled ? "CANCELLED" : "LAST_TEAM_STANDING",
                List.of(new DuelResult.Participant(player, name, "RED", 7, 40, 120, false)));
    }
}
