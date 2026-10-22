package cloud.mineplay.mobarena.economy;

import cloud.mineplay.mobarena.duels.DuelResult;
import cloud.mineplay.mobarena.storage.DuelOutbox;
import cloud.mineplay.mobarena.storage.SettlementStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class DuelOutboxTest {
    @TempDir Path directory;

    @Test void roundtripAndRepeatedSaveKeepExactResult() throws Exception {
        var outbox = new DuelOutbox(directory);
        var result = result();
        outbox.save(result); outbox.save(result);
        var reopened = new DuelOutbox(directory);
        assertEquals(List.of(result), reopened.entries());
        reopened.remove(result.sessionId());
        assertTrue(reopened.entries().isEmpty());
    }

    @Test void conflictingRepeatedSavePreservesOriginalRecord() throws Exception {
        var outbox = new DuelOutbox(directory);
        var result = result();
        outbox.save(result);
        var conflict = new DuelResult(result.sessionId(), result.arenaId(), result.mode(), result.startedAt(), result.finishedAt(),
                "BLUE", false, result.endReason(), result.participants());
        assertThrows(IOException.class, () -> outbox.save(conflict));
        assertEquals(List.of(result), outbox.entries());
    }

    @Test void replayAfterSqlCommitDoesNotDuplicateRoster() throws Exception {
        var outbox = new DuelOutbox(directory.resolve("outbox"));
        var result = result();
        outbox.save(result);
        try (var store = new SettlementStore(directory.resolve("mobarena.db"))) {
            assertTrue(store.recordDuel(result));
            assertFalse(store.recordDuel(outbox.entries().getFirst()));
            outbox.remove(result.sessionId());
            assertEquals(1, store.duelHistory(DuelStoreTest.RED, 1).size());
            assertEquals(0, store.stats(DuelStoreTest.RED).kills());
        }
    }

    @Test void completeUnpublishedWriteIsRecoveredAndDuplicatePublicationIsDeduplicated() throws Exception {
        var outbox = new DuelOutbox(directory);
        var result = result();
        outbox.save(result);
        Path pending = directory.resolve(result.sessionId() + ".pending");
        Path writing = directory.resolve(result.sessionId() + ".writing");
        Files.move(pending, writing);
        assertEquals(List.of(result), outbox.entries());
        assertTrue(Files.exists(pending)); assertFalse(Files.exists(writing));
        Files.copy(pending, writing);
        assertEquals(List.of(result), outbox.entries());
        assertFalse(Files.exists(writing));
    }

    @Test void corruptOrTruncatedRecordIsPreservedForRecovery() throws Exception {
        var outbox = new DuelOutbox(directory);
        var result = result();
        outbox.save(result);
        Path path = directory.resolve(result.sessionId() + ".pending");
        byte[] bytes = Files.readAllBytes(path);
        bytes[10] ^= 1;
        Files.write(path, bytes);
        assertThrows(IOException.class, outbox::entries);
        assertArrayEquals(bytes, Files.readAllBytes(path));
        Files.write(path, new byte[]{1, 2, 3});
        assertThrows(IOException.class, outbox::entries);
        assertTrue(Files.exists(path));
    }

    @Test void filenameMismatchAndUnsupportedChecksummedFormatAreRejected() throws Exception {
        var outbox = new DuelOutbox(directory);
        var result = result();
        outbox.save(result);
        Path pending = directory.resolve(result.sessionId() + ".pending");
        Path renamed = directory.resolve(UUID.randomUUID() + ".pending");
        Files.move(pending, renamed);
        assertThrows(IOException.class, outbox::entries);
        Files.move(renamed, pending);
        byte[] bytes = Files.readAllBytes(pending);
        bytes[7] = 2;
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(bytes, bytes.length - 32));
        System.arraycopy(hash, 0, bytes, bytes.length - 32, hash.length);
        Files.write(pending, bytes);
        assertThrows(IOException.class, outbox::entries);
        assertTrue(Files.exists(pending));
    }

    private static DuelResult result() {
        return DuelStoreTest.result(UUID.randomUUID(), DuelStoreTest.START.plusSeconds(120));
    }
}
