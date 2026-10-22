package cloud.mineplay.mobarena.website;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Separate duel contract; no PvP data is ever encoded as a PvE match.snapshot. */
public final class DuelSnapshotEncoder {
    private static final Gson JSON = new Gson();
    private static final DateTimeFormatter UTC = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();

    private DuelSnapshotEncoder() { }

    public static byte[] encode(Connection connection, UUID sourceId, UUID sessionId, UUID eventId,
                                long revision, Instant observedAt) throws SQLException {
        if (connection.getAutoCommit()) throw new SQLException("Duel snapshot requires an active ledger transaction");
        if (revision <= 0) throw invalid("REVISION");
        var envelope = new JsonObject();
        envelope.addProperty("schema_version", 1);
        envelope.addProperty("event_type", "duel.snapshot");
        envelope.addProperty("event_id", uuid(eventId.toString()));
        envelope.addProperty("source_id", uuid(sourceId.toString()));
        envelope.addProperty("match_revision", Long.toString(revision));
        envelope.addProperty("observed_at", time(observedAt.toString()));
        var duel = new JsonObject();
        long duration;
        try (var statement = connection.prepareStatement("SELECT * FROM duels WHERE session_id=?")) {
            statement.setString(1, sessionId.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw invalid("MISSING_DUEL");
                duel.addProperty("session_id", uuid(rows.getString("session_id")));
                duel.addProperty("arena_id", text(rows.getString("arena_id"), 128));
                duel.addProperty("mode", option(rows.getString("mode"), Set.of("DUEL", "TEAM_DUEL")));
                String start = time(rows.getString("started_at")), finish = time(rows.getString("finished_at"));
                if (Instant.parse(finish).isBefore(Instant.parse(start))) throw invalid("TIMES");
                duration = Duration.between(Instant.parse(start), Instant.parse(finish)).getSeconds();
                duel.addProperty("started_at", start); duel.addProperty("finished_at", finish);
                duel.addProperty("winner", option(rows.getString("winner"), Set.of("RED", "BLUE", "DRAW")));
                duel.addProperty("cancelled", bool(rows.getString("cancelled")));
                duel.addProperty("end_reason", text(rows.getString("end_reason"), 64));
            }
        }
        var participants = new JsonArray();
        var players = new HashSet<String>();
        try (var statement = connection.prepareStatement("SELECT * FROM duel_participants WHERE session_id=? ORDER BY team,player_id LIMIT 1001")) {
            statement.setString(1, sessionId.toString());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (participants.size() >= 1000) throw invalid("ROSTER_SIZE");
                    var participant = new JsonObject();
                    String playerId = uuid(rows.getString("player_id"));
                    if (!players.add(playerId)) throw invalid("DUPLICATE_PLAYER");
                    participant.addProperty("player_id", playerId);
                    participant.addProperty("player_name", text(rows.getString("player_name"), 64));
                    participant.addProperty("team", option(rows.getString("team"), Set.of("RED", "BLUE")));
                    long kills = whole(rows.getString("kills"));
                    if (kills > Integer.MAX_VALUE) throw invalid("KILLS");
                    participant.addProperty("kills", (int) kills);
                    if (!(rows.getObject("damage") instanceof Number)) throw invalid("DAMAGE");
                    double damage = rows.getDouble("damage");
                    if (!Double.isFinite(damage) || damage < 0) throw invalid("DAMAGE");
                    participant.addProperty("damage", damage == 0.0 ? 0.0 : damage);
                    long seconds = whole(rows.getString("participation_seconds"));
                    if (seconds > duration) throw invalid("PARTICIPATION_TIME");
                    participant.addProperty("participation_seconds", Long.toString(seconds));
                    participant.addProperty("eliminated", bool(rows.getString("eliminated")));
                    participants.add(participant);
                }
            }
        }
        // Cancelled lobbies can legitimately have an empty roster.
        if (participants.isEmpty() && !duel.get("cancelled").getAsBoolean()) throw invalid("ROSTER_SIZE");
        duel.add("participants", participants);
        envelope.add("duel", duel);
        byte[] bytes = JSON.toJson(envelope).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MatchSnapshotEncoder.MAXIMUM_BYTES) throw invalid("BODY_TOO_LARGE");
        return bytes;
    }

    private static String uuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value) || id.equals(new UUID(0, 0))) throw invalid("UUID");
            return value;
        } catch (IllegalArgumentException | NullPointerException error) { throw invalid("UUID"); }
    }

    private static long whole(String value) {
        try {
            if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw invalid("INTEGER");
            return Long.parseLong(value);
        } catch (NumberFormatException error) { throw invalid("INTEGER"); }
    }

    private static boolean bool(String value) {
        if (!"0".equals(value) && !"1".equals(value)) throw invalid("BOOLEAN");
        return "1".equals(value);
    }

    private static String option(String value, Set<String> allowed) {
        if (value == null || !allowed.contains(value)) throw invalid("ENUM");
        return value;
    }

    private static String time(String value) {
        try {
            Instant instant = Instant.parse(value);
            int year = instant.atOffset(ZoneOffset.UTC).getYear();
            if (year < 1 || year > 9999) throw invalid("TIMESTAMP");
            return UTC.format(instant);
        } catch (java.time.DateTimeException | NullPointerException error) { throw invalid("TIMESTAMP"); }
    }

    private static String text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum || value.chars().anyMatch(Character::isISOControl)) throw invalid("TEXT");
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) throw invalid("TEXT");
            } else if (Character.isLowSurrogate(current)) throw invalid("TEXT");
        }
        return value;
    }

    private static InvalidSnapshot invalid(String code) { return new InvalidSnapshot("LOCAL_DUEL_" + code); }
    public static final class InvalidSnapshot extends RuntimeException {
        private final String code;
        private InvalidSnapshot(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
