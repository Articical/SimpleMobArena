package cloud.mineplay.mobarena.economy;

import cloud.mineplay.mobarena.duels.DuelResult;
import cloud.mineplay.mobarena.equipment.SnapshotStore;
import cloud.mineplay.mobarena.storage.DatabaseSettings;
import cloud.mineplay.mobarena.storage.SettlementStore;
import cloud.mineplay.mobarena.storage.StorageMigration;
import cloud.mineplay.mobarena.storage.WebsiteExportStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class DuelWebsiteExportTest {
    @TempDir Path directory;
    private static final Instant END = DuelStoreTest.START.plusSeconds(120);
    private static final UUID SOURCE = UUID.fromString("7b240c2f-8b5f-442e-9204-62f5ec7618e5");

    @Test void emitsExplicitDuelContractWithoutPvePaymentFieldsAndIdempotentReplayAddsNoEvent() throws Exception {
        var result = DuelStoreTest.result(new UUID(0, 200), END);
        try (var store = new SettlementStore(directory.resolve("mobarena.db"))) {
            store.configureWebsiteExport(true, SOURCE, END);
            assertTrue(store.recordDuel(result, END));
            assertFalse(store.recordDuel(result, END.plusSeconds(1)));
            var events = store.websiteEvents(END, 10);
            assertEquals(1, events.size());
            var event = events.getFirst();
            assertEquals(WebsiteExportStore.Kind.DUEL, event.kind());
            JsonObject json = json(event);
            assertEquals(Set.of("schema_version", "event_type", "event_id", "source_id", "match_revision", "observed_at", "duel"), json.keySet());
            assertEquals("duel.snapshot", json.get("event_type").getAsString());
            assertEquals("1", json.get("match_revision").getAsString());
            assertTrue(json.get("match_revision").getAsJsonPrimitive().isString());
            JsonObject duel = json.getAsJsonObject("duel");
            assertEquals(Set.of("session_id", "arena_id", "mode", "started_at", "finished_at", "winner", "cancelled", "end_reason", "participants"), duel.keySet());
            assertEquals("TEAM_DUEL", duel.get("mode").getAsString());
            assertEquals("RED", duel.get("winner").getAsString());
            assertEquals(2, duel.getAsJsonArray("participants").size());
            JsonObject participant = duel.getAsJsonArray("participants").get(0).getAsJsonObject();
            assertEquals(Set.of("player_id", "player_name", "team", "kills", "damage", "participation_seconds", "eliminated"), participant.keySet());
            assertTrue(participant.get("participation_seconds").getAsJsonPrimitive().isString());
            assertEquals("BLUE", participant.get("team").getAsString());
            assertTrue(participant.get("eliminated").getAsBoolean());
            assertEquals(0, store.stats(DuelStoreTest.RED).kills());
            fixture("team-duel", event);
        }
    }

    @Test void frozenDuelBytesSurviveRetryQuarantineAcknowledgementAndRestart() throws Exception {
        WebsiteExportStore.Event original;
        Path file = directory.resolve("mobarena.db");
        try (var store = new SettlementStore(file)) {
            store.configureWebsiteExport(true, SOURCE, END);
            store.recordDuel(DuelStoreTest.result(UUID.randomUUID(), END), END);
            original = store.websiteEvents(END, 10).getFirst();
            store.websiteFailed(original.eventId(), "HTTP_503", END.plusSeconds(10), false);
            assertTrue(store.websiteEvents(END.plusSeconds(9), 10).isEmpty());
        }
        try (var store = new SettlementStore(file)) {
            store.configureWebsiteExport(true, SOURCE, END);
            var retry = store.websiteEvents(END.plusSeconds(10), 10).getFirst();
            assertEquals(original.eventId(), retry.eventId()); assertArrayEquals(original.payload(), retry.payload());
            assertEquals(1, retry.attempts());
            store.websiteFailed(retry.eventId(), "HTTP_404", END.plusSeconds(20), true);
            assertEquals(1, store.websiteStatus().quarantined());
            assertEquals("DUEL_HTTP_404", store.websiteStatus().lastFailure());
            assertTrue(store.websiteEvents(END.plusSeconds(30), 10).isEmpty());
            assertTrue(store.websiteRetry(retry.eventId(), END.plusSeconds(31)));
            assertArrayEquals(original.payload(), store.websiteEvents(END.plusSeconds(31), 10).getFirst().payload());
            store.websiteDelivered(original.eventId(), 1, END.plusSeconds(32));
            assertEquals(0, store.websiteStatus().pending()); assertEquals(0, store.websiteStatus().quarantined());
            assertEquals(END.plusSeconds(32), store.websiteStatus().lastAcknowledgedAt());
        }
    }

    @Test void backfillIncludesDisabledCaptureAndCancelledLobbiesAndResumesAfterRestart() throws Exception {
        Path file = directory.resolve("mobarena.db");
        var first = DuelStoreTest.result(new UUID(0, 10), END);
        var second = new DuelResult(new UUID(0, 20), "empty", "DUEL", END, END, "DRAW", true, "CANCELLED", List.of());
        try (var store = new SettlementStore(file)) {
            store.recordDuel(first, END); store.recordDuel(second, END);
            store.configureWebsiteExport(true, SOURCE, END);
            assertTrue(store.websiteEvents(END, 10).isEmpty());
            store.websiteStartBackfill(END);
            assertEquals(1, store.websiteBackfillBatch(1, END));
        }
        try (var store = new SettlementStore(file)) {
            store.configureWebsiteExport(true, SOURCE, END);
            assertTrue(store.websiteStatus().backfillActive());
            assertEquals(1, store.websiteBackfillBatch(1, END));
            assertEquals(0, store.websiteBackfillBatch(1, END));
            assertFalse(store.websiteStatus().backfillActive());
            assertEquals(2, store.websiteEvents(END, 10).size());
            var event = store.websiteEvents(END, 10).stream().filter(value -> value.sessionId().equals(second.sessionId())).findFirst().orElseThrow();
            assertTrue(json(event).getAsJsonObject("duel").get("cancelled").getAsBoolean());
            assertTrue(json(event).getAsJsonObject("duel").getAsJsonArray("participants").isEmpty());
            fixture("cancelled-duel", event);
        }
    }

    @Test void sharedStatusAndBoundedQueueIncludeBothModesWithoutStarvation() throws Exception {
        try (var store = new SettlementStore(directory.resolve("mobarena.db"))) {
            store.configureWebsiteExport(true, SOURCE, END);
            store.recordDuel(DuelStoreTest.result(UUID.randomUUID(), END), END);
            store.prepare(new SessionResult(UUID.randomUUID(), "mobs", END.minusSeconds(120), END, 3, new BigDecimal("100.00"),
                    List.of(new SessionResult.Participant(DuelStoreTest.RED, "Alex", 10, 100, 120)), false), EconomySettings.defaults(), END);
            assertEquals(2, store.websiteStatus().pending());
            assertNotEquals(store.websiteEvents(END, 1).getFirst().kind(), store.websiteEvents(END, 1).getFirst().kind());
            assertEquals(2, store.websiteEvents(END, 10).size());
            assertEquals(1, store.websiteEvents(END, 1).size());
        }
    }

    @Test void malformedDuelIsQuarantinedWithoutLosingItsAuthoritativeRecord() throws Exception {
        var result = new DuelResult(UUID.randomUUID(), "bad\narena", "DUEL", END.minusSeconds(120), END,
                "RED", false, "DONE", DuelStoreTest.result(UUID.randomUUID(), END).participants());
        try (var store = new SettlementStore(directory.resolve("mobarena.db"))) {
            store.configureWebsiteExport(true, SOURCE, END);
            assertTrue(store.recordDuel(result, END));
            assertEquals(result, store.duelMatch(result.sessionId()));
            assertEquals(1, store.websiteStatus().quarantined());
            assertTrue(store.websiteEvents(END, 10).isEmpty());
            assertEquals("DUEL_LOCAL_DUEL_TEXT", store.websiteStatus().lastFailure());
            assertFalse(store.websiteRetry(store.websiteStatus().lastFailureEventId(), END));
        }
    }

    @Test void duelExportIdentityRevisionAndExactBytesSurviveStorageMigration() throws Exception {
        var source = DatabaseSettings.sqlite(directory.resolve("source"));
        var target = DatabaseSettings.sqlite(directory.resolve("target"));
        WebsiteExportStore.Event original;
        try (var store = new SettlementStore(source); var snapshots = new SnapshotStore(source)) {
            store.configureWebsiteExport(true, SOURCE, END);
            store.recordDuel(DuelStoreTest.result(UUID.randomUUID(), END), END);
            original = store.websiteEvents(END, 10).getFirst();
            store.websiteFailed(original.eventId(), "HTTP_503", END.plusSeconds(10), false);
        }
        var report = StorageMigration.migrate(source, target, true);
        assertEquals(1, report.rows().get("website_duel_export_state"));
        assertEquals(1, report.rows().get("website_duel_revisions"));
        assertEquals(1, report.rows().get("website_duel_export_events"));
        try (var store = new SettlementStore(target)) {
            store.configureWebsiteExport(true, SOURCE, END);
            var migrated = store.websiteEvents(END.plusSeconds(10), 10).getFirst();
            assertEquals(original.eventId(), migrated.eventId()); assertEquals(original.revision(), migrated.revision());
            assertArrayEquals(original.payload(), migrated.payload()); assertEquals(1, migrated.attempts());
            assertEquals(WebsiteExportStore.Kind.DUEL, migrated.kind());
        }
    }

    private static JsonObject json(WebsiteExportStore.Event event) {
        return JsonParser.parseString(new String(event.payload(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void fixture(String name, WebsiteExportStore.Event event) throws Exception {
        Path folder = Path.of("build", "duel-contract-fixtures");
        Files.createDirectories(folder);
        Files.write(folder.resolve(name + ".json"), event.payload());
    }
}
