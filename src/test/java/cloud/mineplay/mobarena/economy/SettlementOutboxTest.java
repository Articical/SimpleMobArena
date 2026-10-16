package cloud.mineplay.mobarena.economy;

import static org.junit.jupiter.api.Assertions.*;
import cloud.mineplay.mobarena.storage.SettlementOutbox;
import cloud.mineplay.mobarena.storage.SettlementStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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
                List.of(new SessionResult.Participant(UUID.randomUUID(), "Alex", 10, 45.5, 120)), false, "ELIMINATED");
    }
}
