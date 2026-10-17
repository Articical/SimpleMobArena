package cloud.mineplay.mobarena.website;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Explicit public reporting allowlist. Called only inside the ledger's transaction. */
public final class MatchSnapshotEncoder {
    public static final int MAXIMUM_BYTES = 2 * 1024 * 1024;
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final DateTimeFormatter UTC = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    private static final Set<String> STATUSES = Set.of("PENDING", "PROCESSING", "CONFIRMED", "UNCERTAIN", "FAILED", "WITHHELD", "VOID");

    private MatchSnapshotEncoder() { }

    public static byte[] encode(Connection connection, UUID sourceId, UUID sessionId, UUID eventId,
            long revision, Instant observedAt) throws SQLException {
        if (connection.getAutoCommit()) throw new SQLException("Website snapshot requires an active ledger transaction");
        var envelope = new JsonObject();
        envelope.addProperty("schema_version", 1);
        envelope.addProperty("event_type", "match.snapshot");
        envelope.addProperty("event_id", uuid(eventId.toString()));
        envelope.addProperty("source_id", uuid(sourceId.toString()));
        if (revision <= 0) throw invalid("REVISION");
        envelope.addProperty("match_revision", Long.toString(revision));
        envelope.addProperty("observed_at", timestamp(observedAt.toString()));
        var match = new JsonObject();
        Instant start;
        Instant finish;
        long expectedKills;
        try (var statement = connection.prepareStatement("SELECT session_id,arena_id,started_at,finished_at,completed_waves,total_kills,prize_pot,unallocated_pot,cancelled,end_reason FROM sessions WHERE session_id=?")) {
            statement.setString(1, sessionId.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw invalid("MISSING_MATCH");
                match.addProperty("session_id", uuid(rows.getString("session_id")));
                match.addProperty("arena_id", text(rows.getString("arena_id"), 128, false));
                String started = timestamp(rows.getString("started_at"));
                String finished = timestamp(rows.getString("finished_at"));
                start = Instant.parse(started);
                finish = Instant.parse(finished);
                if (finish.isBefore(start)) throw invalid("MATCH_TIMES");
                match.addProperty("started_at", started);
                match.addProperty("finished_at", finished);
                match.addProperty("completed_waves", integer(rows.getString("completed_waves")));
                expectedKills = whole(rows.getString("total_kills"));
                match.addProperty("total_kills", Long.toString(expectedKills));
                String pot = money(rows.getString("prize_pot"));
                String unallocated = money(rows.getString("unallocated_pot"));
                if (new BigDecimal(unallocated).compareTo(new BigDecimal(pot)) > 0) throw invalid("UNALLOCATED_POT");
                match.addProperty("prize_pot", pot);
                match.addProperty("unallocated_pot", unallocated);
                int cancelled = integer(rows.getString("cancelled"));
                if (cancelled > 1) throw invalid("CANCELLED");
                match.addProperty("cancelled", cancelled == 1);
                match.addProperty("end_reason", text(rows.getString("end_reason"), 64, true));
            }
        }
        var participants = new JsonArray();
        var players = new HashSet<String>();
        var settlements = new HashSet<String>();
        long kills = 0;
        long duration = Duration.between(start, finish).getSeconds();
        // LEFT JOIN detects a broken/missing settlement instead of silently dropping a roster member.
        try (var statement = connection.prepareStatement("SELECT p.player_id,p.player_name,p.kills,p.damage,p.participation_seconds,t.settlement_id,t.share,t.gross_amount,t.intended_amount,t.caps_applied,t.status,t.reserved_at,t.paid_at,t.paid_day FROM participants p LEFT JOIN settlements t ON t.session_id=p.session_id AND t.player_id=p.player_id WHERE p.session_id=? ORDER BY p.player_id LIMIT 1001")) {
            statement.setString(1, sessionId.toString());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (participants.size() >= 1000) throw invalid("ROSTER_SIZE");
                    var participant = new JsonObject();
                    String player = uuid(rows.getString("player_id"));
                    if (!players.add(player)) throw invalid("DUPLICATE_PLAYER");
                    participant.addProperty("player_id", player);
                    participant.addProperty("player_name", text(rows.getString("player_name"), 64, false));
                    int playerKills = integer(rows.getString("kills"));
                    kills += playerKills;
                    participant.addProperty("kills", playerKills);
                    // SQLite's numeric-to-text conversion rounds REAL values to roughly 15
                    // digits. Read the stored numeric value directly so migration to MySQL
                    // cannot change the immutable contribution sent to the website.
                    if (!(rows.getObject("damage") instanceof Number)) throw invalid("DAMAGE");
                    double damage = rows.getDouble("damage");
                    if (!Double.isFinite(damage) || damage < 0) throw invalid("DAMAGE");
                    // SQLite may discard the sign of IEEE negative zero; both providers
                    // must publish the same representation of a zero contribution.
                    participant.addProperty("damage", damage == 0.0 ? 0.0 : damage);
                    long seconds = whole(rows.getString("participation_seconds"));
                    if (seconds > duration) throw invalid("PARTICIPATION_TIME");
                    participant.addProperty("participation_seconds", Long.toString(seconds));
                    participant.add("settlement", settlement(rows, settlements));
                    participants.add(participant);
                }
            }
        }
        if (participants.isEmpty()) throw invalid("ROSTER_SIZE");
        if (expectedKills != kills) throw invalid("KILL_TOTAL");
        match.add("participants", participants);
        envelope.add("match", match);
        byte[] bytes = JSON.toJson(envelope).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAXIMUM_BYTES) throw invalid("BODY_TOO_LARGE");
        return bytes;
    }

    private static JsonObject settlement(ResultSet rows, Set<String> ids) throws SQLException {
        var result = new JsonObject();
        String id = uuid(rows.getString("settlement_id"));
        if (!ids.add(id)) throw invalid("DUPLICATE_SETTLEMENT");
        result.addProperty("settlement_id", id);
        result.addProperty("share", share(rows.getString("share")));
        result.addProperty("gross_amount", money(rows.getString("gross_amount")));
        result.addProperty("intended_amount", money(rows.getString("intended_amount")));
        var caps = new JsonArray();
        String source = rows.getString("caps_applied");
        if (source == null) throw invalid("CAPS");
        if (!source.isEmpty()) {
            String[] entries = source.split(",", -1);
            if (entries.length > 64) throw invalid("CAPS");
            for (String entry : entries) caps.add(text(entry, 128, false));
        }
        result.add("caps_applied", caps);
        String status = rows.getString("status");
        if (status == null || !STATUSES.contains(status)) throw invalid("STATUS");
        result.addProperty("status", status);
        result.addProperty("reserved_at", timestamp(rows.getString("reserved_at")));
        String paid = rows.getString("paid_at");
        String day = rows.getString("paid_day");
        if (paid == null) result.add("paid_at", JsonNull.INSTANCE);
        else {
            paid = timestamp(paid);
            result.addProperty("paid_at", paid);
        }
        if (day == null) result.add("paid_day", JsonNull.INSTANCE);
        else {
            try {
                if (paid == null || !day.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                        || !LocalDate.parse(day).equals(Instant.parse(paid).atOffset(ZoneOffset.UTC).toLocalDate())) throw invalid("PAID_DAY");
            } catch (java.time.DateTimeException exception) { throw invalid("PAID_DAY"); }
            result.addProperty("paid_day", day);
        }
        return result;
    }

    private static String uuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value) || id.equals(new UUID(0, 0))) throw invalid("UUID");
            return value;
        } catch (IllegalArgumentException | NullPointerException exception) { throw invalid("UUID"); }
    }

    private static long whole(String value) {
        try {
            if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw invalid("INTEGER");
            return Long.parseLong(value);
        } catch (NumberFormatException exception) { throw invalid("INTEGER"); }
    }

    private static int integer(String value) {
        long number = whole(value);
        if (number > Integer.MAX_VALUE) throw invalid("INTEGER");
        return (int) number;
    }

    private static String money(String value) {
        try {
            BigDecimal amount = new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
            if (amount.signum() < 0 || amount.precision() > 20) throw invalid("MONEY");
            return amount.toPlainString();
        } catch (NumberFormatException | ArithmeticException | NullPointerException exception) { throw invalid("MONEY"); }
    }

    private static String share(String value) {
        try {
            BigDecimal share = new BigDecimal(value).stripTrailingZeros();
            if (share.signum() < 0 || share.compareTo(BigDecimal.ONE) > 0 || share.scale() > 16) throw invalid("SHARE");
            return share.toPlainString();
        } catch (NumberFormatException | NullPointerException exception) { throw invalid("SHARE"); }
    }

    private static String timestamp(String value) {
        try {
            Instant instant = Instant.parse(value);
            int year = instant.atOffset(ZoneOffset.UTC).getYear();
            if (year < 1 || year > 9999) throw invalid("TIMESTAMP");
            return UTC.format(instant);
        } catch (java.time.DateTimeException | NullPointerException exception) { throw invalid("TIMESTAMP"); }
    }

    private static String text(String value, int maximum, boolean allowEmpty) {
        if (value == null || value.length() > maximum || (!allowEmpty && value.isBlank())
                || value.chars().anyMatch(Character::isISOControl)) throw invalid("TEXT");
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) throw invalid("TEXT");
            } else if (Character.isLowSurrogate(current)) throw invalid("TEXT");
        }
        return value;
    }

    private static InvalidSnapshot invalid(String code) { return new InvalidSnapshot("LOCAL_" + code); }
    public static final class InvalidSnapshot extends RuntimeException {
        private final String code;
        private InvalidSnapshot(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
