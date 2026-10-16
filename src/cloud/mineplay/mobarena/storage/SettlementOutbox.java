package cloud.mineplay.mobarena.storage;

import cloud.mineplay.mobarena.economy.EconomySettings;
import cloud.mineplay.mobarena.economy.SessionResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Durable local handoff for completed results, including when the remote SQL backend is unavailable. */
public final class SettlementOutbox {
    private final Path directory;
    public SettlementOutbox(Path directory) { this.directory = directory; }

    public synchronized void save(SessionResult result, EconomySettings settings) throws IOException {
        Files.createDirectories(directory);
        byte[] encoded = encode(new Entry(result, settings));
        Path destination = directory.resolve(result.sessionId() + ".pending");
        if (Files.exists(destination)) {
            if (!Arrays.equals(encoded, Files.readAllBytes(destination))) throw new IOException("Conflicting outbox entry for session " + result.sessionId());
            return;
        }
        Path temporary = directory.resolve(result.sessionId() + ".writing");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(encoded);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        // If the filesystem cannot atomically publish a durable record, fail closed rather than silently weaken recovery.
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
    }

    public synchronized List<Entry> entries() throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        var entries = new ArrayList<Entry>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".pending")
                    || path.getFileName().toString().endsWith(".writing")).toList()) {
                if (Files.size(file) > 16 * 1024 * 1024) throw new IOException("Oversized settlement outbox record: " + file.getFileName());
                Entry entry = decode(Files.readAllBytes(file));
                String filename = file.getFileName().toString();
                if (filename.equals(entry.result().sessionId() + ".writing")) {
                    Path destination = directory.resolve(entry.result().sessionId() + ".pending");
                    if (Files.exists(destination)) {
                        if (!Arrays.equals(Files.readAllBytes(file), Files.readAllBytes(destination))) throw new IOException("Conflicting outbox publication records");
                        Files.delete(file);
                        continue;
                    }
                    Files.move(file, destination, StandardCopyOption.ATOMIC_MOVE);
                } else if (!filename.equals(entry.result().sessionId() + ".pending")) throw new IOException("Outbox filename/session mismatch");
                entries.add(entry);
            }
        }
        entries.sort(Comparator.comparing((Entry entry) -> entry.result().finishedAt()).thenComparing(entry -> entry.result().sessionId()));
        return List.copyOf(entries);
    }

    public synchronized void remove(UUID session) throws IOException { Files.deleteIfExists(directory.resolve(session + ".pending")); }

    private static byte[] encode(Entry entry) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x534D4131); out.writeInt(1);
            SessionResult r = entry.result();
            out.writeUTF(r.sessionId().toString()); out.writeUTF(r.arenaId());
            out.writeUTF(r.startedAt().toString()); out.writeUTF(r.finishedAt().toString());
            out.writeInt(r.completedWaves()); out.writeUTF(r.prizePot().toPlainString());
            out.writeBoolean(r.cancelled()); out.writeUTF(r.endReason()); out.writeInt(r.participants().size());
            for (var p : r.participants()) {
                out.writeUTF(p.playerId().toString()); out.writeUTF(p.playerName()); out.writeInt(p.kills());
                out.writeDouble(p.damage()); out.writeLong(p.participationSeconds());
            }
            EconomySettings s = entry.settings();
            out.writeBoolean(s.enabled()); out.writeUTF(s.baseWaveReward().toPlainString()); out.writeBoolean(s.multiplyByWave());
            out.writeBoolean(s.playerScaling()); out.writeUTF(s.additionalPlayerMultiplier().toPlainString()); out.writeUTF(s.maximumMultiplier().toPlainString());
            out.writeUTF(s.maximumPot().toPlainString()); out.writeUTF(s.maximumPlayerPayout().toPlainString()); out.writeUTF(s.maximumDailyPayout().toPlainString());
            out.writeUTF(s.rewardCooldown().toString()); out.writeInt(s.minimumRewardWave()); out.writeLong(s.minimumParticipationSeconds());
            out.writeInt(s.scale()); out.writeLong(s.shortSessionSeconds()); out.writeUTF(s.shortSessionMultiplier().toPlainString()); out.writeBoolean(s.auditLogging());
        }
        byte[] payload = bytes.toByteArray();
        bytes.writeBytes(hash(payload));
        return bytes.toByteArray();
    }

    private static Entry decode(byte[] encoded) throws IOException {
        if (encoded.length < 40) throw new IOException("Truncated settlement outbox record");
        byte[] payload = Arrays.copyOf(encoded, encoded.length - 32);
        if (!MessageDigest.isEqual(hash(payload), Arrays.copyOfRange(encoded, payload.length, encoded.length))) throw new IOException("Settlement outbox checksum mismatch");
        try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readInt() != 0x534D4131 || in.readInt() != 1) throw new IOException("Unsupported settlement outbox format");
            UUID id = UUID.fromString(in.readUTF()); String arena = in.readUTF();
            Instant started = Instant.parse(in.readUTF()), finished = Instant.parse(in.readUTF());
            int waves = in.readInt(); BigDecimal pot = decimal(in); boolean cancelled = in.readBoolean(); String reason = in.readUTF();
            int count = in.readInt(); if (count < 0 || count > 10000) throw new IOException("Invalid outbox participant count");
            var participants = new ArrayList<SessionResult.Participant>();
            for (int index = 0; index < count; index++) participants.add(new SessionResult.Participant(UUID.fromString(in.readUTF()),
                    in.readUTF(), in.readInt(), in.readDouble(), in.readLong()));
            var result = new SessionResult(id, arena, started, finished, waves, pot, participants, cancelled, reason);
            var settings = new EconomySettings(in.readBoolean(), decimal(in), in.readBoolean(), in.readBoolean(), decimal(in), decimal(in),
                    decimal(in), decimal(in), decimal(in), Duration.parse(in.readUTF()), in.readInt(), in.readLong(), in.readInt(), in.readLong(), decimal(in), in.readBoolean());
            if (in.available() != 0) throw new IOException("Unexpected outbox trailing data");
            return new Entry(result, settings);
        } catch (IllegalArgumentException exception) { throw new IOException("Invalid settlement outbox payload", exception); }
    }

    private static BigDecimal decimal(DataInputStream in) throws IOException { return new BigDecimal(in.readUTF()); }
    private static byte[] hash(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public record Entry(SessionResult result, EconomySettings settings) { }
}
