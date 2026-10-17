package cloud.mineplay.mobarena.economy;

import static cloud.mineplay.mobarena.economy.RewardCalculatorTest.assertMoney;
import static cloud.mineplay.mobarena.economy.RewardCalculatorTest.policy;
import static org.junit.jupiter.api.Assertions.*;
import cloud.mineplay.mobarena.storage.SettlementStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettlementStoreTest {
    @TempDir Path directory;
    private static final UUID PLAYER = new UUID(0, 1);
    private static final Instant START = Instant.parse("2026-10-16T12:00:00Z");

    @Test void uniqueSessionAndPlayerKeysPreventDuplicateStatisticsAndPayments() throws Exception {
        try (var store = store()) {
            var result = result(START, "100", false);
            assertTrue(store.prepare(result, settings(), START.plusSeconds(120)));
            assertFalse(store.prepare(result, settings(), START.plusSeconds(121)));
            assertEquals(1, store.payouts(result.sessionId()).size());
            assertEquals(1, store.stats(PLAYER).sessions());
            assertEquals(10, store.stats(PLAYER).kills());
        }
    }

    @Test void claimsAreDurableAndCannotBeRepeated() throws Exception {
        UUID id;
        try (var store = store()) {
            var result = result(START, "100", false);
            store.prepare(result, settings(), START.plusSeconds(120));
            id = store.pending(1).getFirst().settlementId();
            assertNotNull(store.claim(id, settings(), START.plusSeconds(120)));
            assertNull(store.claim(id, settings(), START.plusSeconds(120)));
            assertEquals(Settlement.Status.PROCESSING, store.get(id).status());
        }
        try (var reopened = store()) {
            assertEquals(1, reopened.recover(START.plusSeconds(121)));
            assertEquals(Settlement.Status.UNCERTAIN, reopened.get(id).status());
            assertTrue(reopened.pending(10).isEmpty());
            assertEquals(0, reopened.recover(START.plusSeconds(122)));
        }
    }

    @Test void pendingAndUncertainPaymentsReserveDailyCapAcrossMidnight() throws Exception {
        var limits = policy("1000", "1000", "100", 0, 0, 0, 0, "1");
        try (var store = store()) {
            var first = result(START, "70", false);
            store.prepare(first, limits, START.plusSeconds(120));
            var payment = store.claim(store.pending(1).getFirst().settlementId(), limits, START.plusSeconds(120));
            store.complete(payment.settlementId(), Settlement.Status.UNCERTAIN, "timeout", START.plusSeconds(121));
            Instant nextDay = START.plusSeconds(86400);
            var second = result(nextDay, "70", false);
            store.prepare(second, limits, nextDay.plusSeconds(120));
            assertMoney("30", store.payouts(second.sessionId()).getFirst().intendedAmount());
            assertTrue(store.payouts(second.sessionId()).getFirst().capsApplied().contains("DAILY_CAP"));
        }
    }

    @Test void overlappingSessionsCannotBypassRewardCooldown() throws Exception {
        try (var store = store()) {
            var first = result(START, "100", false);
            store.prepare(first, EconomySettings.defaults(), START.plusSeconds(120));
            var overlapping = result(START.plusSeconds(30), "100", false);
            store.prepare(overlapping, EconomySettings.defaults(), START.plusSeconds(150));
            var payment = store.payouts(overlapping.sessionId()).getFirst();
            assertMoney("0", payment.intendedAmount());
            assertTrue(payment.capsApplied().contains("REWARD_COOLDOWN"));
            var afterCooldown = result(START.plusSeconds(1020), "100", false);
            store.prepare(afterCooldown, EconomySettings.defaults(), START.plusSeconds(1140));
            assertMoney("100", store.payouts(afterCooldown.sessionId()).getFirst().intendedAmount());
        }
    }

    @Test void confirmedPaymentsCountTowardActualPaymentDateAndStatistics() throws Exception {
        var limits = policy("1000", "1000", "100", 0, 0, 0, 0, "1");
        try (var store = store()) {
            var result = result(START, "70", false);
            store.prepare(result, limits, START.plusSeconds(120));
            Instant paymentTime = START.plusSeconds(86400);
            var payment = store.claim(store.pending(1).getFirst().settlementId(), limits, paymentTime);
            store.complete(payment.settlementId(), Settlement.Status.CONFIRMED, "", paymentTime);
            var sameDay = result(paymentTime.plusSeconds(600), "70", false);
            store.prepare(sameDay, limits, paymentTime.plusSeconds(720));
            assertMoney("30", store.payouts(sameDay.sessionId()).getFirst().intendedAmount());
            assertMoney("70", store.stats(PLAYER).earned());
            assertEquals(3, store.stats(PLAYER).highestWave());
        }
    }

    @Test void loweredDailyCapIsRecheckedBeforePayment() throws Exception {
        try (var store = store()) {
            var result = result(START, "100", false);
            store.prepare(result, settings(), START.plusSeconds(120));
            var lower = policy("1000", "1000", "30", 0, 0, 0, 0, "1");
            var payment = store.claim(store.pending(1).getFirst().settlementId(), lower, START.plusSeconds(121));
            assertMoney("30", payment.intendedAmount());
            assertTrue(payment.capsApplied().contains("CAP_RECHECK_AT_PAYMENT"));
        }
    }

    @Test void missingProviderLeavesPendingLedgerUntouched() throws Exception {
        try (var store = store()) {
            var result = result(START, "100", false);
            store.prepare(result, settings(), START.plusSeconds(120));
            assertNull(SettlementPayments.claimNext(store, settings(), false, START.plusSeconds(121)));
            assertEquals(Settlement.Status.PENDING, store.payouts(result.sessionId()).getFirst().status());
            assertNotNull(SettlementPayments.claimNext(store, settings(), true, START.plusSeconds(122)));
        }
    }

    @Test void providerDisappearingBeforeDepositCanSafelyReturnToPending() throws Exception {
        try (var store = store()) {
            store.prepare(result(START, "100", false), settings(), START.plusSeconds(120));
            var payment = SettlementPayments.claimNext(store, settings(), true, START.plusSeconds(121));
            store.complete(payment.settlementId(), Settlement.Status.PENDING, "No deposit attempted", START.plusSeconds(122));
            var retried = SettlementPayments.claimNext(store, settings(), true, START.plusSeconds(123));
            assertEquals(payment.settlementId(), retried.settlementId());
        }
    }

    @Test void cancelledGamesNeverCreatePayableEntries() throws Exception {
        try (var store = store()) {
            var result = result(START, "100", true);
            store.prepare(result, settings(), START.plusSeconds(120));
            assertTrue(store.pending(10).isEmpty());
            assertMoney("0", store.payouts(result.sessionId()).getFirst().intendedAmount());
            assertEquals(1, store.stats(PLAYER).sessions());
            assertEquals(10, store.stats(PLAYER).kills());
        }
    }

    @Test void reconciliationRequiresUncertainStateAndNeverRetriesPayment() throws Exception {
        try (var store = store()) {
            store.prepare(result(START, "100", false), settings(), START.plusSeconds(120));
            var pending = store.pending(1).getFirst();
            assertTrue(store.reconcile(pending.settlementId(), "VOID", "admin", START).contains("Only UNCERTAIN"));
            var payment = store.claim(pending.settlementId(), settings(), START.plusSeconds(121));
            assertTrue(store.reconcile(payment.settlementId(), "CONFIRM", "admin", START).contains("Only UNCERTAIN"));
            store.complete(payment.settlementId(), Settlement.Status.UNCERTAIN, "timeout", START.plusSeconds(122));
            assertTrue(store.reconcile(payment.settlementId(), "RETRY", "admin", START).contains("Use CONFIRM"));
            assertTrue(store.reconcile(payment.settlementId(), "CONFIRM", "admin", START.plusSeconds(123)).contains("No money was sent"));
            assertEquals(Settlement.Status.CONFIRMED, store.get(payment.settlementId()).status());
            assertMoney("100", store.stats(PLAYER).earned());
            assertTrue(store.pending(10).isEmpty());
        }
    }

    @Test void voidReleasesReservationButDoesNotAwardEarnings() throws Exception {
        var limits = policy("1000", "1000", "100", 0, 0, 0, 0, "1");
        try (var store = store()) {
            store.prepare(result(START, "100", false), limits, START.plusSeconds(120));
            var payment = store.claim(store.pending(1).getFirst().settlementId(), limits, START.plusSeconds(121));
            store.complete(payment.settlementId(), Settlement.Status.UNCERTAIN, "timeout", START.plusSeconds(122));
            store.reconcile(payment.settlementId(), "VOID", "admin", START.plusSeconds(123));
            var next = result(START.plusSeconds(300), "100", false);
            store.prepare(next, limits, START.plusSeconds(420));
            assertMoney("100", store.payouts(next.sessionId()).getFirst().intendedAmount());
            assertMoney("0", store.stats(PLAYER).earned());
        }
    }

    @Test void failedProviderResponseIsAuditedAndNotRetried() throws Exception {
        try (var store = store()) {
            store.prepare(result(START, "100", false), settings(), START.plusSeconds(120));
            var payment = store.claim(store.pending(1).getFirst().settlementId(), settings(), START.plusSeconds(121));
            store.complete(payment.settlementId(), Settlement.Status.FAILED, "Provider rejected deposit", START.plusSeconds(122));
            assertTrue(store.pending(10).isEmpty());
            assertEquals("Provider rejected deposit", store.get(payment.settlementId()).error());
            assertMoney("0", store.stats(PLAYER).earned());
        }
    }

    @Test void historyLinksFullMatchRosterAndKeepsPendingSeparateFromConfirmed() throws Exception {
        UUID secondPlayer = new UUID(0, 2);
        try (var store = store()) {
            var result = new SessionResult(UUID.randomUUID(), "roof", START, START.plusSeconds(120), 3, new BigDecimal("100"),
                    List.of(new SessionResult.Participant(PLAYER, "Alex", 3, 60, 120),
                            new SessionResult.Participant(secondPlayer, "Sam", 1, 20, 60)), false, "ALL_ELIMINATED");
            store.prepare(result, settings(), START.plusSeconds(120));
            var history = store.history(PLAYER, 1);
            assertEquals(1, history.size());
            assertMoney("75", history.getFirst().intendedAmount());
            assertMoney("0", history.getFirst().confirmedAmount());
            var match = store.match(history.getFirst().sessionId());
            assertEquals(2, match.participants().size());
            assertEquals("Alex", match.participants().getFirst().playerName());
            assertEquals("Sam", match.participants().get(1).playerName());
            assertEquals("ALL_ELIMINATED", match.endReason());
            assertEquals(4, match.totalKills());
            assertTrue(store.history(PLAYER, 2).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> store.history(PLAYER, 0));
            assertNull(store.match(UUID.randomUUID()));
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("mobarena.db"));
                 var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM mobarena_match_performance")) {
                assertTrue(rows.next()); assertEquals(2, rows.getInt(1));
            }
        }
    }

    @Test void leaderboardAggregatesExactMinorUnitsWithoutBinaryFloatingPoint() throws Exception {
        var limits = policy("1000000000", "1000000000", "1000000000", 0, 0, 0, 0, "1");
        try (var store = store()) {
            int index = 0;
            for (String amount : List.of("0.29", "1.01", "999999998.69")) {
                Instant start = START.plusSeconds(300L * index++);
                var result = result(start, amount, false);
                store.prepare(result, limits, start.plusSeconds(120));
                var payment = store.claim(store.payouts(result.sessionId()).getFirst().settlementId(), limits, start.plusSeconds(121));
                store.complete(payment.settlementId(), Settlement.Status.CONFIRMED, "", start.plusSeconds(122));
            }
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("mobarena.db"));
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT confirmed_earned_minor,typeof(confirmed_earned_minor) FROM mobarena_leaderboard")) {
                assertTrue(rows.next()); assertEquals(99999999999L, rows.getLong(1)); assertEquals("integer", rows.getString(2));
            }
            assertMoney("999999999.99", store.stats(PLAYER).earned());
        }
    }

    @Test void statisticsAndLeaderboardUseTheLatestRecordedNameWithoutExternalLookups() throws Exception {
        try (var store = store()) {
            var newest = new SessionResult(new UUID(0, 3), "spawn", START.plusSeconds(300), START.plusSeconds(420), 3,
                    BigDecimal.TEN, List.of(new SessionResult.Participant(PLAYER, ".Bedrock Player", 3, 60, 120)), false);
            var older = new SessionResult(new UUID(0, 1), "spawn", START, START.plusSeconds(120), 3,
                    BigDecimal.TEN, List.of(new SessionResult.Participant(PLAYER, "PreviousName", 2, 40, 120)), false);
            var sameFinish = new SessionResult(new UUID(0, 2), "spawn", START.plusSeconds(300), START.plusSeconds(420), 3,
                    BigDecimal.TEN, List.of(new SessionResult.Participant(PLAYER, "EarlierTie", 1, 20, 120)), false);
            store.prepare(newest, settings(), START.plusSeconds(420));
            store.prepare(older, settings(), START.plusSeconds(421));
            store.prepare(sameFinish, settings(), START.plusSeconds(422));
            assertEquals(".Bedrock Player", store.stats(PLAYER).playerName());
            assertEquals(".Bedrock Player", store.top(10).getFirst().playerName());
            assertEquals(6, store.stats(PLAYER).kills());
        }
    }

    @Test void missingNamesRetainTheFullPlayerUuid() throws Exception {
        try (var store = store()) {
            assertEquals(PLAYER.toString(), store.stats(PLAYER).playerName());
            store.prepare(result(START, "100", false), settings(), START.plusSeconds(120));
            assertEquals(PLAYER.toString(), store.stats(PLAYER).playerName());
        }
        assertEquals(PLAYER.toString(), new PlayerStatistics(PLAYER, 0, 0, 0, 0, BigDecimal.ZERO, null).playerName());
        assertEquals(PLAYER.toString(), new PlayerStatistics(PLAYER, 0, 0, 0, 0, BigDecimal.ZERO, " ").playerName());
    }

    private SettlementStore store() throws Exception { return new SettlementStore(directory.resolve("mobarena.db")); }
    private static EconomySettings settings() { return policy("1000", "1000", "10000", 0, 0, 0, 0, "1"); }
    private static SessionResult result(Instant start, String pot, boolean cancelled) {
        return new SessionResult(UUID.randomUUID(), "spawn", start, start.plusSeconds(120), 3,
                new BigDecimal(pot), List.of(new SessionResult.Participant(PLAYER, 10, 200, 120)), cancelled);
    }
}
