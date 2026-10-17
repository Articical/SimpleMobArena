package cloud.mineplay.mobarena.economy;

import static cloud.mineplay.mobarena.economy.RewardCalculatorTest.policy;
import static org.junit.jupiter.api.Assertions.*;
import cloud.mineplay.mobarena.storage.SettlementStore;
import cloud.mineplay.mobarena.storage.WebsiteExportStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebsiteExportStoreTest {
    @TempDir Path directory;
    private static final UUID SOURCE = UUID.fromString("2ec55caa-8614-41ba-8d50-b77334bd9ac3");
    private static final UUID PLAYER = new UUID(0, 1);
    private static final UUID BEDROCK = UUID.fromString("00000000-0000-0000-0009-01f123456789");
    private static final Instant START = Instant.parse("2026-10-17T18:00:00.123456789Z");
    private static final Instant END = START.plusSeconds(180);

    @Test void completeAllowlistedSnapshotsMatchReceiverContractAndPreserveBedrockAndNanos() throws Exception {
        try (var store = enabled()) {
            SessionResult match = match(UUID.randomUUID());
            assertTrue(store.prepare(match, settings(), END));
            assertFalse(store.prepare(match, settings(), END));
            var events = store.websiteEvents(END.plusSeconds(100), 100);
            assertEquals(1, events.size());
            JsonObject envelope = json(events.getFirst());
            assertEquals(7, envelope.size());
            assertEquals("1", envelope.get("match_revision").getAsString());
            var body = envelope.getAsJsonObject("match");
            assertEquals(11, body.size());
            assertEquals("750.00", body.get("prize_pot").getAsString());
            assertEquals("0.00", body.get("unallocated_pot").getAsString());
            assertEquals("27", body.get("total_kills").getAsString());
            assertEquals("2026-10-17T18:00:00.123456789Z", body.get("started_at").getAsString());
            assertEquals(2, body.getAsJsonArray("participants").size());
            var first = participant(body, PLAYER);
            var bedrock = participant(body, BEDROCK);
            assertEquals(".Bedrock Player", bedrock.get("player_name").getAsString());
            assertEquals("180", first.get("participation_seconds").getAsString());
            JsonObject pending = first.getAsJsonObject("settlement");
            assertEquals(9, pending.size());
            assertTrue(pending.has("paid_at")); assertTrue(pending.get("paid_at").isJsonNull());
            assertTrue(pending.has("paid_day")); assertTrue(pending.get("paid_day").isJsonNull());
            assertEquals("500.00", pending.get("intended_amount").getAsString());
            assertEquals("0.6666666666666666", pending.get("share").getAsString());
            assertTrue(pending.getAsJsonArray("caps_applied").isEmpty());
            assertFalse(new String(events.getFirst().payload(), StandardCharsets.UTF_8).contains("settings"));
            fixture("initial-pending", events.getFirst());
            UUID id = store.payouts(match.sessionId()).stream().filter(p -> p.playerId().equals(PLAYER)).findFirst().orElseThrow().settlementId();
            store.claim(id, settings(), END.plusSeconds(1));
            fixture("processing", store.websiteEvents(END.plusSeconds(100), 100).getLast());
            store.complete(id, Settlement.Status.CONFIRMED, "PRIVATE_PROVIDER_ERROR", END.plusSeconds(2));
            var confirmed = store.websiteEvents(END.plusSeconds(100), 100).getLast();
            fixture("confirmed", confirmed);
            var confirmedBody = json(confirmed).getAsJsonObject("match");
            assertEquals("3", json(confirmed).get("match_revision").getAsString());
            var settled = participant(confirmedBody, PLAYER).getAsJsonObject("settlement");
            assertEquals("CONFIRMED", settled.get("status").getAsString());
            assertEquals("2026-10-17", settled.get("paid_day").getAsString());
            assertFalse(new String(confirmed.payload(), StandardCharsets.UTF_8).contains("PRIVATE_PROVIDER_ERROR"));
            assertEquals(immutable(body), immutable(confirmedBody));
        }
    }

    @Test void queueInsertionFailureRollsBackInitialLedgerAndRevisionTogether() throws Exception {
        try (var store = enabled()) {
            sql("CREATE TRIGGER reject_export BEFORE INSERT ON website_export_events BEGIN SELECT RAISE(ABORT,'injected queue failure'); END");
            SessionResult match = match(UUID.randomUUID());
            assertThrows(SQLException.class, () -> store.prepare(match, settings(), END));
            assertNull(store.match(match.sessionId()));
            assertEquals(0, scalar("SELECT COUNT(*) FROM website_match_revisions"));
            assertEquals(0, scalar("SELECT COUNT(*) FROM settlements"));
            sql("DROP TRIGGER reject_export");
            assertTrue(store.prepare(match, settings(), END));
            assertEquals(1, store.websiteEvents(END, 10).getFirst().revision());
        }
    }

    @Test void queueInsertionFailureRollsBackClaimAndCapRecheckBeforeAnyDeposit() throws Exception {
        try (var store = enabled()) {
            var match = match(UUID.randomUUID()); store.prepare(match, settings(), END);
            UUID id = store.payouts(match.sessionId()).getFirst().settlementId();
            sql("CREATE TRIGGER reject_export BEFORE INSERT ON website_export_events BEGIN SELECT RAISE(ABORT,'injected queue failure'); END");
            var smaller = policy("1000", "20", "10000", 0, 0, 0, 0, "1");
            assertThrows(SQLException.class, () -> store.claim(id, smaller, END.plusSeconds(1)));
            assertEquals(Settlement.Status.PENDING, store.get(id).status());
            assertEquals(0, new BigDecimal("500").compareTo(store.get(id).intendedAmount()));
            assertEquals(0, store.match(match.sessionId()).unallocatedPot().signum());
            assertEquals(1, scalar("SELECT revision FROM website_match_revisions"));
        }
    }

    @Test void everyPaymentMutationQueuesCompleteRosterAndMonotonicRevision() throws Exception {
        UUID id;
        try (var store = enabled()) {
            var match = match(UUID.randomUUID()); store.prepare(match, settings(), END);
            id = store.payouts(match.sessionId()).getFirst().settlementId();
            store.claim(id, settings(), END.plusSeconds(1));
            store.complete(id, Settlement.Status.PENDING, "No deposit attempted", END.plusSeconds(2));
            store.claim(id, settings(), END.plusSeconds(3));
        }
        try (var store = enabled()) {
            assertEquals(1, store.recover(END.plusSeconds(4)));
            assertEquals(0, store.recover(END.plusSeconds(5)));
            store.reconcile(id, "VOID", "PRIVATE_ADMIN", END.plusSeconds(6));
            var events = store.websiteEvents(END.plusSeconds(100), 100);
            assertEquals(6, events.size());
            List<String> statuses = List.of("PENDING", "PROCESSING", "PENDING", "PROCESSING", "UNCERTAIN", "VOID");
            for (int index = 0; index < events.size(); index++) {
                var event = events.get(index);
                assertEquals(index + 1, event.revision());
                var body = json(event).getAsJsonObject("match");
                assertEquals(2, body.getAsJsonArray("participants").size());
                assertEquals(statuses.get(index), participant(body, PLAYER).getAsJsonObject("settlement").get("status").getAsString());
                assertEquals(immutable(json(events.getFirst()).getAsJsonObject("match")), immutable(body));
                assertFalse(new String(event.payload(), StandardCharsets.UTF_8).contains("PRIVATE_ADMIN"));
            }
            assertEquals("500.00", json(events.getLast()).getAsJsonObject("match").get("unallocated_pot").getAsString());
            fixture("reconciled-void", events.getLast());
        }
    }

    @Test void failedAndCapReducedWithheldStatesAreExportedWithoutNewPayments() throws Exception {
        try (var store = enabled()) {
            var match = match(UUID.randomUUID()); store.prepare(match, settings(), END);
            var payouts = store.payouts(match.sessionId());
            UUID first = payouts.getFirst().settlementId();
            var lower = policy("1000", "20", "10000", 0, 0, 0, 0, "1");
            store.claim(first, lower, END.plusSeconds(1));
            store.complete(first, Settlement.Status.FAILED, "Provider refused", END.plusSeconds(2));
            var noFunds = policy("1000", "1000", "0", 0, 0, 0, 0, "1");
            assertNull(store.claim(payouts.getLast().settlementId(), noFunds, END.plusSeconds(3)));
            var body = json(store.websiteEvents(END.plusSeconds(10), 100).getLast()).getAsJsonObject("match");
            assertEquals("750.00", body.get("unallocated_pot").getAsString());
            assertEquals("FAILED", participant(body, PLAYER).getAsJsonObject("settlement").get("status").getAsString());
            assertEquals("WITHHELD", participant(body, BEDROCK).getAsJsonObject("settlement").get("status").getAsString());
            assertTrue(store.pending(10).isEmpty());
            fixture("failed-and-withheld", store.websiteEvents(END.plusSeconds(10), 100).getLast());
        }
    }

    @Test void recoveryCommitIsAtomicWithItsNewSnapshot() throws Exception {
        UUID id;
        try (var store = enabled()) {
            var match = match(UUID.randomUUID()); store.prepare(match, settings(), END);
            id = store.payouts(match.sessionId()).getFirst().settlementId();
            store.claim(id, settings(), END.plusSeconds(1));
            sql("CREATE TRIGGER reject_export BEFORE INSERT ON website_export_events BEGIN SELECT RAISE(ABORT,'injected queue failure'); END");
        }
        try (var store = enabled()) {
            assertThrows(SQLException.class, () -> store.recover(END.plusSeconds(2)));
            assertEquals(Settlement.Status.PROCESSING, store.get(id).status());
            assertEquals(2, store.websiteEvents(END.plusSeconds(100), 100).size());
            sql("DROP TRIGGER reject_export");
            assertEquals(1, store.recover(END.plusSeconds(3)));
            store.reconcile(id, "CONFIRM", "operator", END.plusSeconds(4));
            assertEquals(4, store.websiteEvents(END.plusSeconds(100), 100).size());
            assertEquals(0, new BigDecimal("500").compareTo(store.stats(PLAYER).earned()));
        }
    }

    @Test void retriesRestartAndQuarantinePreserveExactBytesAndIdentity() throws Exception {
        WebsiteExportStore.Event original;
        try (var store = enabled()) {
            store.prepare(match(UUID.randomUUID()), settings(), END);
            original = store.websiteEvents(END, 1).getFirst();
            byte[] mutable = original.payload(); mutable[0] = 0;
            assertNotEquals(0, original.payload()[0]);
            store.websiteFailed(original.eventId(), "NETWORK_ERROR", END.plusSeconds(30), false);
            assertTrue(store.websiteEvents(END.plusSeconds(29), 10).isEmpty());
        }
        try (var store = enabled()) {
            var retry = store.websiteEvents(END.plusSeconds(30), 10).getFirst();
            assertEquals(1, retry.attempts()); assertEquals(original.eventId(), retry.eventId());
            assertArrayEquals(original.payload(), retry.payload()); assertEquals(original.revision(), retry.revision());
            store.websiteFailed(original.eventId(), "HTTP_409", END.plusSeconds(60), true);
            assertTrue(store.websiteEvents(END.plusSeconds(100), 10).isEmpty());
            assertEquals(1, store.websiteStatus().quarantined());
            assertEquals(original.eventId(), store.websiteStatus().lastFailureEventId());
            assertTrue(store.websiteRetry(original.eventId(), END.plusSeconds(61)));
            assertArrayEquals(original.payload(), store.websiteEvents(END.plusSeconds(61), 1).getFirst().payload());
            assertThrows(IllegalArgumentException.class, () -> store.websiteDelivered(original.eventId(), 0, END));
        }
    }

    @Test void acknowledgmentRequiresCorrectRevisionAndPersistsOnlyAfterCommit() throws Exception {
        try (var store = enabled()) {
            var match = match(UUID.randomUUID()); store.prepare(match, settings(), END);
            store.claim(store.payouts(match.sessionId()).getFirst().settlementId(), settings(), END.plusSeconds(1));
            var event = store.websiteEvents(END.plusSeconds(10), 10).getLast();
            assertThrows(SQLException.class, () -> store.websiteDelivered(event.eventId(), 1, END.plusSeconds(2)));
            sql("CREATE TRIGGER reject_ack BEFORE UPDATE OF last_acknowledged_at ON website_export_state BEGIN SELECT RAISE(ABORT,'injected acknowledgement failure'); END");
            assertThrows(SQLException.class, () -> store.websiteDelivered(event.eventId(), 9, END.plusSeconds(2)));
            assertEquals(2, store.websiteStatus().pending());
            assertNull(store.websiteStatus().lastAcknowledgedAt());
            sql("DROP TRIGGER reject_ack");
            store.websiteDelivered(event.eventId(), 9, END.plusSeconds(3));
            assertEquals(1, store.websiteStatus().pending());
            assertEquals(END.plusSeconds(3), store.websiteStatus().lastAcknowledgedAt());
            assertFalse(store.websiteRetry(event.eventId(), END.plusSeconds(4)));
            assertEquals(9, scalar("SELECT stored_revision FROM website_export_events WHERE delivery_status='DELIVERED'"));
        }
    }

    @Test void disabledIntegrationRetainsLineageAndQueueAndRejectsSilentIdentityChanges() throws Exception {
        try (var store = enabled()) {
            store.prepare(match(UUID.randomUUID()), settings(), END);
            assertThrows(IllegalArgumentException.class, () -> store.configureWebsiteExport(true, UUID.randomUUID(), END));
            store.configureWebsiteExport(false, null, END);
            store.prepare(match(UUID.randomUUID()), settings(), END);
            assertTrue(store.websiteEvents(END.plusSeconds(100), 100).isEmpty());
            assertEquals(1, store.websiteStatus().pending());
            assertEquals(SOURCE, store.websiteStatus().sourceId());
        }
        try (var store = new SettlementStore(directory.resolve("ledger.db"))) {
            assertTrue(store.websiteEvents(END.plusSeconds(100), 100).isEmpty());
            assertEquals(SOURCE, store.websiteStatus().sourceId());
            assertThrows(IllegalArgumentException.class, () -> store.configureWebsiteExport(true, null, END));
            assertThrows(IllegalArgumentException.class, () -> store.configureWebsiteExport(true, new UUID(0, 0), END));
            store.configureWebsiteExport(true, SOURCE, END);
            assertEquals(1, store.websiteEvents(END.plusSeconds(100), 100).size());
        }
    }

    @Test void backfillResumesAcrossRestartAndSerializesWithNewerPaymentState() throws Exception {
        UUID firstMatch = new UUID(1, 1);
        UUID secondMatch = new UUID(2, 2);
        UUID payment;
        try (var store = new SettlementStore(directory.resolve("ledger.db"))) {
            store.prepare(match(firstMatch), settings(), END);
            store.prepare(match(secondMatch), settings(), END);
            store.configureWebsiteExport(true, SOURCE, END);
            store.websiteStartBackfill(END);
            assertEquals(1, store.websiteBackfillBatch(1, END));
            assertTrue(store.websiteStatus().backfillActive());
            payment = store.payouts(firstMatch).getFirst().settlementId();
            store.claim(payment, settings(), END.plusSeconds(1));
            store.complete(payment, Settlement.Status.CONFIRMED, null, END.plusSeconds(2));
        }
        try (var store = enabled()) {
            store.websiteStartBackfill(END.plusSeconds(3));
            assertEquals(1, store.websiteBackfillBatch(1, END.plusSeconds(3)));
            assertEquals(0, store.websiteBackfillBatch(1, END.plusSeconds(4)));
            assertFalse(store.websiteStatus().backfillActive());
            var events = store.websiteEvents(END.plusSeconds(100), 100);
            assertEquals(4, events.size());
            assertEquals(3, events.stream().filter(e -> e.sessionId().equals(firstMatch)).count());
            assertEquals(1, events.stream().filter(e -> e.sessionId().equals(secondMatch)).count());
            assertEquals(Settlement.Status.CONFIRMED, store.get(payment).status());
        }
    }

    @Test void invalidHistoricMoneyIsDurablyQuarantinedWithoutRoundingOrBlockingPayments() throws Exception {
        try (var store = enabled()) {
            var match = match(UUID.randomUUID()); store.prepare(match, settings(), END);
            sql("UPDATE settlements SET gross_amount='500.001' WHERE player_id='" + PLAYER + "'");
            UUID id = store.payouts(match.sessionId()).getFirst().settlementId();
            assertNotNull(store.claim(id, settings(), END.plusSeconds(1)));
            assertEquals(Settlement.Status.PROCESSING, store.get(id).status());
            assertEquals(1, store.websiteStatus().pending()); assertEquals(1, store.websiteStatus().quarantined());
            assertEquals("LOCAL_MONEY", store.websiteStatus().lastFailure());
            assertFalse(store.websiteRetry(store.websiteStatus().lastFailureEventId(), END.plusSeconds(2)));
            assertEquals(1, scalar("SELECT COUNT(*) FROM website_export_events WHERE payload IS NULL AND delivery_status='QUARANTINED'"));
            store.complete(id, Settlement.Status.CONFIRMED, null, END.plusSeconds(2));
            assertEquals(2, store.websiteStatus().quarantined());
            assertEquals(Settlement.Status.CONFIRMED, store.get(id).status());
        }
    }

    @Test void captureGapRestartsActiveBackfillSoEarlierChangesAndNewLowerIdsAreIncluded() throws Exception {
        UUID first = new UUID(2, 2);
        UUID later = new UUID(3, 3);
        UUID insertedBehindCursor = new UUID(1, 1);
        try (var store = new SettlementStore(directory.resolve("ledger.db"))) {
            store.prepare(match(first), settings(), END);
            store.prepare(match(later), settings(), END);
            store.configureWebsiteExport(true, SOURCE, END);
            store.websiteStartBackfill(END);
            assertEquals(1, store.websiteBackfillBatch(1, END));
            UUID payment = store.payouts(first).getFirst().settlementId();
            store.configureWebsiteExport(false, null, END.plusSeconds(1));
            store.claim(payment, settings(), END.plusSeconds(2));
            store.complete(payment, Settlement.Status.CONFIRMED, null, END.plusSeconds(3));
            store.prepare(match(insertedBehindCursor), settings(), END.plusSeconds(4));
            assertTrue(store.websiteStatus().backfillActive());
            assertEquals(1, store.websiteStatus().pending());
        }
        try (var store = enabled()) {
            assertEquals(3, store.websiteBackfillBatch(10, END.plusSeconds(5)));
            assertEquals(0, store.websiteBackfillBatch(10, END.plusSeconds(6)));
            assertFalse(store.websiteStatus().backfillActive());
            var events = store.websiteEvents(END.plusSeconds(10), 100);
            assertEquals(4, events.size());
            assertEquals(1, events.stream().filter(e -> e.sessionId().equals(insertedBehindCursor)).count());
            assertEquals(1, events.stream().filter(e -> e.sessionId().equals(later)).count());
            var refreshed = events.stream().filter(e -> e.sessionId().equals(first) && e.revision() == 2).findFirst().orElseThrow();
            assertEquals("CONFIRMED", participant(json(refreshed).getAsJsonObject("match"), PLAYER)
                    .getAsJsonObject("settlement").get("status").getAsString());
        }
    }

    @Test void missingSourceMetadataCannotAssignFreshLineageToExistingRevisionOrQueueRows() throws Exception {
        WebsiteExportStore.Event original;
        try (var store = enabled()) {
            store.prepare(match(UUID.randomUUID()), settings(), END);
            original = store.websiteEvents(END, 10).getFirst();
        }
        sql("DELETE FROM website_export_state");
        try (var store = new SettlementStore(directory.resolve("ledger.db"))) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> store.configureWebsiteExport(true, UUID.randomUUID(), END));
            assertEquals("WEBSITE_SOURCE_STATE_MISSING", failure.getMessage());
            assertEquals(0, scalar("SELECT COUNT(*) FROM website_export_state"));
            assertEquals(1, scalar("SELECT COUNT(*) FROM website_match_revisions"));
            assertEquals(1, scalar("SELECT COUNT(*) FROM website_export_events"));
            assertTrue(store.websiteEvents(END, 10).isEmpty());
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("ledger.db"));
                    var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT event_id,payload FROM website_export_events")) {
                assertTrue(rows.next());
                assertEquals(original.eventId().toString(), rows.getString(1));
                assertArrayEquals(original.payload(), rows.getBytes(2));
            }
            store.configureWebsiteExport(false, null, END);
            assertEquals(1, store.websiteStatus().pending());
            sql("DELETE FROM website_match_revisions");
            assertThrows(IllegalArgumentException.class, () -> store.configureWebsiteExport(true, SOURCE, END));
            assertEquals(0, scalar("SELECT COUNT(*) FROM website_export_state"));
            sql("INSERT INTO website_match_revisions(session_id,revision) SELECT session_id,revision FROM website_export_events");
            sql("DELETE FROM website_export_events");
            assertThrows(IllegalArgumentException.class, () -> store.configureWebsiteExport(true, SOURCE, END));
            assertEquals(0, scalar("SELECT COUNT(*) FROM website_export_state"));
        }
    }

    @Test void cancelledZeroKillAndUnknownCapCodesRemainVisible() throws Exception {
        try (var store = enabled()) {
            var match = new SessionResult(UUID.randomUUID(), "spawn", START, END, 0, BigDecimal.ZERO,
                    List.of(new SessionResult.Participant(PLAYER, 0, 0, 0)), true);
            store.prepare(match, settings(), END);
            sql("UPDATE settlements SET caps_applied='CANCELLED,FUTURE_CAP_CODE'");
            store.websiteStartBackfill(END); store.websiteBackfillBatch(10, END.plusSeconds(1));
            var event = store.websiteEvents(END.plusSeconds(10), 100).getLast();
            var body = json(event).getAsJsonObject("match");
            assertTrue(body.get("cancelled").getAsBoolean());
            assertEquals("0", body.get("total_kills").getAsString());
            var participant = participant(body, PLAYER);
            assertEquals(PLAYER.toString(), participant.get("player_name").getAsString());
            assertEquals("FUTURE_CAP_CODE", participant.getAsJsonObject("settlement").getAsJsonArray("caps_applied").get(1).getAsString());
            fixture("cancelled-zero-kill", event);
        }
    }

    @Test void invalidParticipationAndMissingSettlementAreQuarantinedRatherThanTruncated() throws Exception {
        try (var store = enabled()) {
            var match = match(UUID.randomUUID()); store.prepare(match, settings(), END);
            sql("UPDATE participants SET participation_seconds=181");
            store.websiteStartBackfill(END); store.websiteBackfillBatch(10, END);
            assertEquals("LOCAL_PARTICIPATION_TIME", store.websiteStatus().lastFailure());
            sql("UPDATE participants SET participation_seconds=180");
            sql("DELETE FROM settlement_audit");
            sql("DELETE FROM settlements WHERE player_id='" + BEDROCK + "'");
            store.websiteBackfillBatch(10, END); store.websiteStartBackfill(END); store.websiteBackfillBatch(10, END);
            assertEquals("LOCAL_UUID", store.websiteStatus().lastFailure());
        }
    }

    @Test void overLimitRostersAndBodiesAreRetainedAsQuarantineWithoutPartialExports() throws Exception {
        try (var store = enabled()) {
            var roster = java.util.stream.IntStream.rangeClosed(1, 1001)
                    .mapToObj(i -> new SessionResult.Participant(new UUID(0, i), 0, 0, 0)).toList();
            var oversizedRoster = new SessionResult(UUID.randomUUID(), "spawn", START, END, 0,
                    BigDecimal.ZERO, roster, true);
            store.prepare(oversizedRoster, settings(), END);
            assertEquals("LOCAL_ROSTER_SIZE", store.websiteStatus().lastFailure());
            assertEquals(1001, store.match(oversizedRoster.sessionId()).participants().size());
            assertTrue(store.websiteEvents(END, 10).isEmpty());
            var validRoster = new SessionResult(UUID.randomUUID(), "spawn", START, END, 0,
                    BigDecimal.ZERO, roster.subList(0, 1000), true);
            store.prepare(validRoster, settings(), END.plusSeconds(1));
            assertEquals(1, store.websiteStatus().pending());
            String manyCaps = String.join(",", java.util.Collections.nCopies(64, "X".repeat(128)));
            sql("UPDATE settlements SET caps_applied='" + manyCaps + "' WHERE session_id='" + validRoster.sessionId() + "'");
            store.websiteStartBackfill(END); store.websiteBackfillBatch(10, END.plusSeconds(2));
            assertEquals(1, scalar("SELECT COUNT(*) FROM website_export_events WHERE failure_code='LOCAL_BODY_TOO_LARGE'"));
            assertEquals(1000, store.match(validRoster.sessionId()).participants().size());
        }
    }

    @Test void bigintStringsDoNotOverflowAndUntrustedSqlDamageDoesNotCoerceToZero() throws Exception {
        try (var store = enabled()) {
            Instant future = Instant.parse("2326-10-17T18:00:00.123456789Z");
            var match = new SessionResult(UUID.randomUUID(), "spawn", START, future, 3, BigDecimal.ZERO,
                    List.of(new SessionResult.Participant(PLAYER, Integer.MAX_VALUE, 0, 3_000_000_000L),
                            new SessionResult.Participant(BEDROCK, Integer.MAX_VALUE, 0, 0)), false);
            store.prepare(match, settings(), future);
            var event = store.websiteEvents(future, 10).getFirst();
            var body = json(event).getAsJsonObject("match");
            assertEquals("4294967294", body.get("total_kills").getAsString());
            assertEquals("3000000000", participant(body, PLAYER).get("participation_seconds").getAsString());
            fixture("bigint-statistics", event);
            sql("UPDATE participants SET damage='not-a-number'");
            store.websiteStartBackfill(future); store.websiteBackfillBatch(10, future);
            assertEquals("LOCAL_DAMAGE", store.websiteStatus().lastFailure());
        }
    }

    @Test void sqliteRealDamageRetainsEveryDoubleBitAcrossSnapshotRevisions() throws Exception {
        double damage = 123.45678901234567;
        try (var store = enabled()) {
            var match = new SessionResult(UUID.randomUUID(), "spawn", START, END, 3, new BigDecimal("1.00"),
                    List.of(new SessionResult.Participant(PLAYER, "PrecisePlayer", 1, damage, 120),
                            new SessionResult.Participant(BEDROCK, ".ZeroContribution", 0, -0.0, 120)), false);
            store.prepare(match, settings(), END);
            var original = store.websiteEvents(END, 10).getFirst();
            var payment = store.payouts(match.sessionId()).getFirst();
            store.claim(payment.settlementId(), settings(), END.plusSeconds(1));
            store.complete(payment.settlementId(), Settlement.Status.CONFIRMED, null, END.plusSeconds(2));
            for (var event : store.websiteEvents(END.plusSeconds(3), 10)) {
                var exported = participant(json(event).getAsJsonObject("match"), PLAYER).get("damage");
                assertEquals(Double.doubleToLongBits(damage), Double.doubleToLongBits(exported.getAsDouble()));
                assertEquals("123.45678901234567", exported.toString());
                assertEquals("0.0", participant(json(event).getAsJsonObject("match"), BEDROCK).get("damage").toString());
            }
            fixture("precise-double-damage", original);
        }
    }

    private SettlementStore enabled() throws Exception {
        var store = new SettlementStore(directory.resolve("ledger.db"));
        store.configureWebsiteExport(true, SOURCE, START);
        return store;
    }
    private static EconomySettings settings() { return policy("1000", "1000", "10000", 0, 0, 0, 0, "1"); }
    private static SessionResult match(UUID id) {
        return new SessionResult(id, "spawn", START, END, 3, new BigDecimal("750"), List.of(
                new SessionResult.Participant(PLAYER, "ExamplePlayer", 18, 340.5, 180),
                new SessionResult.Participant(BEDROCK, ".Bedrock Player", 9, 250.25, 120)), false, "No active participants remain.");
    }
    private void sql(String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("ledger.db")); var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private long scalar(String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("ledger.db")); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { assertTrue(rows.next()); return rows.getLong(1); }
    }
    private static JsonObject json(WebsiteExportStore.Event event) { return JsonParser.parseString(new String(event.payload(), StandardCharsets.UTF_8)).getAsJsonObject(); }
    private static JsonObject participant(JsonObject match, UUID id) {
        return match.getAsJsonArray("participants").asList().stream().map(e -> e.getAsJsonObject())
                .filter(p -> p.get("player_id").getAsString().equals(id.toString())).findFirst().orElseThrow();
    }
    private static JsonObject immutable(JsonObject source) {
        var copy = source.deepCopy(); copy.remove("unallocated_pot");
        for (var value : copy.getAsJsonArray("participants")) {
            var settlement = value.getAsJsonObject().getAsJsonObject("settlement");
            for (String key : List.of("intended_amount", "caps_applied", "status", "paid_at", "paid_day")) settlement.remove(key);
        }
        return copy;
    }
    private static void fixture(String name, WebsiteExportStore.Event event) throws Exception {
        Path output = Path.of("build", "contract-fixtures"); Files.createDirectories(output);
        Files.write(output.resolve(name + ".json"), event.payload());
    }
}
