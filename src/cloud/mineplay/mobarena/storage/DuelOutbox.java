package cloud.mineplay.mobarena.storage;

import cloud.mineplay.mobarena.duels.DuelResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Durable PvP handoff, deliberately separate from payout and website-export records. */
public final class DuelOutbox {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final Path directory;

    public DuelOutbox(Path directory) { this.directory = directory; }

    public synchronized void save(DuelResult result) throws IOException {
        Files.createDirectories(directory);
        byte[] encoded = encode(result);
        Path destination = directory.resolve(result.sessionId() + ".pending");
        if (Files.exists(destination)) {
            if (!Arrays.equals(encoded, read(destination))) throw new IOException("Conflicting duel outbox result for " + result.sessionId());
            return;
        }
        Path temporary = directory.resolve(result.sessionId() + ".writing");
        if (Files.exists(temporary) && !Arrays.equals(encoded, read(temporary))) {
            throw new IOException("Conflicting unfinished duel outbox result for " + result.sessionId());
        }
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(encoded);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
    }

    public synchronized List<DuelResult> entries() throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        var results = new LinkedHashMap<UUID, DuelResult>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".pending")
                    || path.getFileName().toString().endsWith(".writing")).toList()) {
                byte[] bytes = read(file);
                DuelResult result = decode(bytes);
                String filename = file.getFileName().toString();
                if (filename.equals(result.sessionId() + ".writing")) {
                    Path destination = directory.resolve(result.sessionId() + ".pending");
                    if (Files.exists(destination)) {
                        if (!Arrays.equals(bytes, read(destination))) throw new IOException("Conflicting duel outbox publication records");
                        Files.delete(file);
                    } else Files.move(file, destination, StandardCopyOption.ATOMIC_MOVE);
                } else if (!filename.equals(result.sessionId() + ".pending")) {
                    throw new IOException("Duel outbox filename/session mismatch");
                }
                DuelResult previous = results.putIfAbsent(result.sessionId(), result);
                if (previous != null && !previous.equals(result)) throw new IOException("Conflicting duel outbox records");
            }
        }
        return results.values().stream().sorted(Comparator.comparing(DuelResult::finishedAt)
                .thenComparing(DuelResult::sessionId)).toList();
    }

    public synchronized void remove(UUID sessionId) throws IOException {
        Files.deleteIfExists(directory.resolve(sessionId + ".pending"));
    }

    private static byte[] read(Path file) throws IOException {
        if (Files.size(file) > MAX_BYTES) throw new IOException("Oversized duel outbox record: " + file.getFileName());
        return Files.readAllBytes(file);
    }

    private static byte[] encode(DuelResult result) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(0x534D4431); out.writeInt(1);
            out.writeUTF(result.sessionId().toString()); out.writeUTF(result.arenaId()); out.writeUTF(result.mode());
            out.writeUTF(result.startedAt().toString()); out.writeUTF(result.finishedAt().toString());
            out.writeUTF(result.winner()); out.writeBoolean(result.cancelled()); out.writeUTF(result.endReason());
            out.writeInt(result.participants().size());
            for (var participant : result.participants()) {
                out.writeUTF(participant.playerId().toString()); out.writeUTF(participant.playerName());
                out.writeUTF(participant.team()); out.writeInt(participant.kills()); out.writeDouble(participant.damage());
                out.writeLong(participant.participationSeconds()); out.writeBoolean(participant.eliminated());
            }
        }
        byte[] payload = bytes.toByteArray();
        bytes.writeBytes(hash(payload));
        byte[] encoded = bytes.toByteArray();
        if (encoded.length > MAX_BYTES) throw new IOException("Duel result is too large");
        return encoded;
    }

    private static DuelResult decode(byte[] encoded) throws IOException {
        if (encoded.length < 40) throw new IOException("Truncated duel outbox record");
        byte[] payload = Arrays.copyOf(encoded, encoded.length - 32);
        if (!MessageDigest.isEqual(hash(payload), Arrays.copyOfRange(encoded, payload.length, encoded.length))) {
            throw new IOException("Duel outbox checksum mismatch");
        }
        try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readInt() != 0x534D4431 || in.readInt() != 1) throw new IOException("Unsupported duel outbox format");
            UUID sessionId = UUID.fromString(in.readUTF());
            String arenaId = in.readUTF(), mode = in.readUTF();
            Instant startedAt = Instant.parse(in.readUTF()), finishedAt = Instant.parse(in.readUTF());
            String winner = in.readUTF(); boolean cancelled = in.readBoolean(); String reason = in.readUTF();
            int count = in.readInt();
            if (count < 0 || count > 10000) throw new IOException("Invalid duel outbox participant count");
            var participants = new ArrayList<DuelResult.Participant>();
            for (int index = 0; index < count; index++) {
                participants.add(new DuelResult.Participant(UUID.fromString(in.readUTF()), in.readUTF(), in.readUTF(),
                        in.readInt(), in.readDouble(), in.readLong(), in.readBoolean()));
            }
            if (in.available() != 0) throw new IOException("Unexpected duel outbox trailing data");
            return new DuelResult(sessionId, arenaId, mode, startedAt, finishedAt, winner, cancelled, reason, participants);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid duel outbox payload", exception);
        }
    }

    private static byte[] hash(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
