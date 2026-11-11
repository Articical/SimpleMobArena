package cloud.mineplay.mobarena.economy;

import static org.junit.jupiter.api.Assertions.*;
import cloud.mineplay.mobarena.storage.SettlementOutbox;
import cloud.mineplay.mobarena.storage.SettlementStore;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettlementOutboxTest {
    @TempDir Path directory;

    @Test void durableRoundtripFreezesResultsAndSettingsDuringDatabaseOutage() throws Exception {
        var outbox = new SettlementOutbox(directory.resolve("outbox"));
        var result = result();
        var settings = EconomySettings.defaults();
        outbox.save(result, settings);
        outbox.save(result, settings);
        var reopened = new SettlementOutbox(directory.resolve("outbox"));
        assertEquals(List.of(new SettlementOutbox.Entry(result, settings)), reopened.entries());
        reopened.remove(result.sessionId());
        assertTrue(reopened.entries().isEmpty());
    }

    @Test void replayAfterCommitBeforeOutboxDeletionCannotDuplicateMoneyOrStatistics() throws Exception {
        var outbox = new SettlementOutbox(directory.resolve("outbox"));
        var result = result();
        outbox.save(result, EconomySettings.defaults());
        try (var store = new SettlementStore(directory.resolve("mobarena.db"))) {
            assertTrue(store.prepare(result, EconomySettings.defaults(), result.finishedAt()));
            // Simulated crash left the committed result in the local outbox.
            for (var entry : outbox.entries()) {
                assertFalse(store.prepare(entry.result(), entry.settings(), result.finishedAt().plusSeconds(1)));
                outbox.remove(entry.result().sessionId());
            }
            assertEquals(1, store.stats(result.participants().getFirst().playerId()).sessions());
            assertEquals(1, store.payouts(result.sessionId()).size());
            assertEquals(Map.of("ZOMBIE", 6L, "SKELETON", 4L), store.mobKills(result.participants().getFirst().playerId()));
        }
    }

    @Test void legacyPendingResultsRemainReadableAndIdempotentAfterFormatUpgrade() throws Exception {
        var typed = result();
        var player = typed.participants().getFirst();
        var legacy = new SessionResult(typed.sessionId(), typed.arenaId(), typed.startedAt(), typed.finishedAt(),
                typed.completedWaves(), typed.prizePot(), List.of(new SessionResult.Participant(player.playerId(),
                player.playerName(), player.kills(), player.damage(), player.participationSeconds())), false, typed.endReason());
        Path folder = directory.resolve("outbox");
        Files.createDirectories(folder);
        Path file = folder.resolve(legacy.sessionId() + ".pending");
        byte[] original = versionOne(legacy, EconomySettings.defaults());
        Files.write(file, original);
        var outbox = new SettlementOutbox(folder);
        assertEquals(legacy, outbox.entries().getFirst().result());
        assertTrue(outbox.entries().getFirst().result().participants().getFirst().mobKills().isEmpty());
        outbox.save(legacy, EconomySettings.defaults());
        assertArrayEquals(original, Files.readAllBytes(file));
        assertThrows(IOException.class, () -> outbox.save(typed, EconomySettings.defaults()));
        try (var store = new SettlementStore(directory.resolve("mobarena.db"))) {
            assertTrue(store.prepare(outbox.entries().getFirst().result(), EconomySettings.defaults(), legacy.finishedAt()));
            assertEquals(10, store.stats(player.playerId()).kills());
            assertTrue(store.mobKills(player.playerId()).isEmpty());
        }
    }

    @Test void corruptedOutboxIsNotSilentlyDropped() throws Exception {
        var outbox = new SettlementOutbox(directory.resolve("outbox"));
        var result = result();
        outbox.save(result, EconomySettings.defaults());
        Path file = directory.resolve("outbox").resolve(result.sessionId() + ".pending");
        byte[] content = Files.readAllBytes(file); content[10] ^= 1; Files.write(file, content);
        assertThrows(IOException.class, outbox::entries);
        assertTrue(Files.isRegularFile(file));
    }

    @Test void recoversFullyWrittenRecordAfterCrashBeforeAtomicPublication() throws Exception {
        Path folder = directory.resolve("outbox");
        var outbox = new SettlementOutbox(folder);
        var result = result();
        outbox.save(result, EconomySettings.defaults());
        Files.move(folder.resolve(result.sessionId() + ".pending"), folder.resolve(result.sessionId() + ".writing"));
        assertEquals(result, outbox.entries().getFirst().result());
        assertTrue(Files.exists(folder.resolve(result.sessionId() + ".pending")));
        assertFalse(Files.exists(folder.resolve(result.sessionId() + ".writing")));
    }

    private static SessionResult result() {
        Instant now = Instant.parse("2026-10-16T12:00:00Z");
        return new SessionResult(UUID.randomUUID(), "roof", now, now.plusSeconds(120), 3, new BigDecimal("123.45"),
                List.of(new SessionResult.Participant(UUID.randomUUID(), "Alex", 10, 45.5, 120, Map.of("ZOMBIE", 6, "SKELETON", 4))), false, "ELIMINATED");
    }

    /** The original wire layout, deliberately independent of the current outbox encoder. */
    private static byte[] versionOne(SessionResult r, EconomySettings s) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(0x534D4131); out.writeInt(1);
            out.writeUTF(r.sessionId().toString()); out.writeUTF(r.arenaId());
            out.writeUTF(r.startedAt().toString()); out.writeUTF(r.finishedAt().toString());
            out.writeInt(r.completedWaves()); out.writeUTF(r.prizePot().toPlainString());
            out.writeBoolean(r.cancelled()); out.writeUTF(r.endReason()); out.writeInt(r.participants().size());
            for (var p : r.participants()) {
                out.writeUTF(p.playerId().toString()); out.writeUTF(p.playerName()); out.writeInt(p.kills());
                out.writeDouble(p.damage()); out.writeLong(p.participationSeconds());
            }
            out.writeBoolean(s.enabled()); out.writeUTF(s.baseWaveReward().toPlainString()); out.writeBoolean(s.multiplyByWave());
            out.writeBoolean(s.playerScaling()); out.writeUTF(s.additionalPlayerMultiplier().toPlainString()); out.writeUTF(s.maximumMultiplier().toPlainString());
            out.writeUTF(s.maximumPot().toPlainString()); out.writeUTF(s.maximumPlayerPayout().toPlainString()); out.writeUTF(s.maximumDailyPayout().toPlainString());
            out.writeUTF(s.rewardCooldown().toString()); out.writeInt(s.minimumRewardWave()); out.writeLong(s.minimumParticipationSeconds());
            out.writeInt(s.scale()); out.writeLong(s.shortSessionSeconds()); out.writeUTF(s.shortSessionMultiplier().toPlainString()); out.writeBoolean(s.auditLogging());
        }
        bytes.writeBytes(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        return bytes.toByteArray();
    }
}
