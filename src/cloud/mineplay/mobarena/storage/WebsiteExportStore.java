package cloud.mineplay.mobarena.storage;

import cloud.mineplay.mobarena.website.MatchSnapshotEncoder;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** SQL-only export queue sharing the authoritative ledger connection and its transaction. */
public final class WebsiteExportStore {
    private static final DateTimeFormatter UTC = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    private final Connection connection;
    private final boolean mysql;
    private boolean enabled;

    WebsiteExportStore(Connection connection, boolean mysql) {
        this.connection = connection;
        this.mysql = mysql;
    }

    void configure(boolean requestedEnabled, UUID requestedSource) throws SQLException {
        if (!requestedEnabled) {
            enabled = false;
            // Changes during a capture gap may fall behind an active scan's cursor. Keep the
            // administrator's backfill intent, but rescan its full range after re-enabling.
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE website_export_state SET backfill_cursor=NULL WHERE id=1 AND backfill_active<>0");
            }
            return;
        }
        enabled = false;
        if (requestedSource == null || requestedSource.equals(new UUID(0, 0))) throw new IllegalArgumentException("WEBSITE_SOURCE_REQUIRED");
        UUID current = source(false);
        if (current == null) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT CASE WHEN EXISTS(SELECT 1 FROM website_match_revisions) OR EXISTS(SELECT 1 FROM website_export_events) THEN 1 ELSE 0 END")) {
                if (!rows.next() || rows.getLong(1) != 0) throw new IllegalArgumentException("WEBSITE_SOURCE_STATE_MISSING");
            }
            try (var statement = connection.prepareStatement("INSERT INTO website_export_state(id,source_id,backfill_active) VALUES(1,?,0)")) {
                statement.setString(1, requestedSource.toString());
                statement.executeUpdate();
            }
        } else if (!current.equals(requestedSource)) throw new IllegalArgumentException("WEBSITE_SOURCE_MISMATCH");
        enabled = true;
    }

    void enqueue(UUID sessionId, Instant now) throws SQLException {
        if (!enabled) return;
        UUID source = source(true);
        if (source == null) throw new SQLException("Website export source is missing");
        long previous = 0;
        try (var statement = connection.prepareStatement("SELECT revision FROM website_match_revisions WHERE session_id=?")) {
            statement.setString(1, sessionId.toString());
            try (var rows = statement.executeQuery()) { if (rows.next()) previous = rows.getLong(1); }
        }
        if (previous < 0 || previous == Long.MAX_VALUE) throw new SQLException("Website revision exhausted");
        long revision = previous + 1;
        try (var statement = connection.prepareStatement(previous == 0
                ? "INSERT INTO website_match_revisions(revision,session_id) VALUES(?,?)"
                : "UPDATE website_match_revisions SET revision=? WHERE session_id=?")) {
            statement.setLong(1, revision);
            statement.setString(2, sessionId.toString());
            if (statement.executeUpdate() != 1) throw new SQLException("Website revision allocation failed");
        }
        UUID eventId = UUID.randomUUID();
        byte[] payload = null;
        String failure = null;
        try { payload = MatchSnapshotEncoder.encode(connection, source, sessionId, eventId, revision, now); }
        catch (MatchSnapshotEncoder.InvalidSnapshot invalid) { failure = invalid.code(); }
        // A malformed historical match is retained for operator action without blocking a Vault state transition.
        // SQL failure here still rolls back both the ledger mutation and revision allocation.
        try (var statement = connection.prepareStatement("INSERT INTO website_export_events(event_id,session_id,revision,payload,created_at,attempts,next_attempt_at,delivery_status,failure_code) VALUES(?,?,?,?,?,0,?,?,?)")) {
            statement.setString(1, eventId.toString());
            statement.setString(2, sessionId.toString());
            statement.setLong(3, revision);
            statement.setBytes(4, payload);
            statement.setString(5, time(now));
            statement.setString(6, time(now));
            statement.setString(7, failure == null ? "PENDING" : "QUARANTINED");
            statement.setString(8, failure);
            statement.executeUpdate();
        }
        if (failure != null) recordFailure(eventId, failure);
    }

    List<Event> events(Instant now, int limit) throws SQLException {
        if (!enabled) return List.of();
        int bounded = Math.clamp(limit, 1, 100);
        var result = new ArrayList<Event>();
        try (var statement = connection.prepareStatement("SELECT event_id,session_id,revision,payload,attempts FROM website_export_events WHERE delivery_status='PENDING' AND next_attempt_at<=? AND payload IS NOT NULL ORDER BY next_attempt_at,created_at,session_id,revision LIMIT ?")) {
            statement.setString(1, time(now));
            statement.setInt(2, bounded);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) result.add(new Event(UUID.fromString(rows.getString(1)), UUID.fromString(rows.getString(2)),
                        rows.getLong(3), rows.getBytes(4), rows.getInt(5)));
            }
        }
        return List.copyOf(result);
    }

    void delivered(UUID eventId, long storedRevision, Instant now) throws SQLException {
        if (storedRevision < 1) throw new IllegalArgumentException("Invalid acknowledgement revision");
        try (var statement = connection.prepareStatement("UPDATE website_export_events SET delivery_status='DELIVERED',acknowledged_at=?,stored_revision=?,failure_code=NULL WHERE event_id=? AND revision<=? AND payload IS NOT NULL AND delivery_status<>'DELIVERED'")) {
            statement.setString(1, time(now));
            statement.setLong(2, storedRevision);
            statement.setString(3, eventId.toString());
            statement.setLong(4, storedRevision);
            if (statement.executeUpdate() != 1) throw new SQLException("Website acknowledgement does not match an undelivered event");
        }
        try (var statement = connection.prepareStatement("UPDATE website_export_state SET last_acknowledged_at=? WHERE id=1")) {
            statement.setString(1, time(now));
            statement.executeUpdate();
        }
    }

    void failed(UUID eventId, String safeCode, Instant nextAttempt, boolean quarantine) throws SQLException {
        String code = safeCode(safeCode);
        try (var statement = connection.prepareStatement("UPDATE website_export_events SET attempts=CASE WHEN attempts<2147483647 THEN attempts+1 ELSE attempts END,next_attempt_at=?,delivery_status=?,failure_code=? WHERE event_id=? AND delivery_status<>'DELIVERED'")) {
            statement.setString(1, time(nextAttempt));
            statement.setString(2, quarantine ? "QUARANTINED" : "PENDING");
            statement.setString(3, code);
            statement.setString(4, eventId.toString());
            if (statement.executeUpdate() == 1) recordFailure(eventId, code);
        }
    }

    boolean retry(UUID eventId, Instant now) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE website_export_events SET delivery_status='PENDING',next_attempt_at=? WHERE event_id=? AND delivery_status<>'DELIVERED' AND payload IS NOT NULL")) {
            statement.setString(1, time(now));
            statement.setString(2, eventId.toString());
            return statement.executeUpdate() == 1;
        }
    }

    Status status() throws SQLException {
        UUID source = null;
        Instant acknowledged = null;
        String failure = null;
        UUID failureEvent = null;
        boolean backfill = false;
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT source_id,backfill_active,last_acknowledged_at,last_failure,last_failure_event_id FROM website_export_state WHERE id=1")) {
            if (rows.next()) {
                source = UUID.fromString(rows.getString(1));
                backfill = rows.getInt(2) != 0;
                acknowledged = instant(rows.getString(3));
                failure = rows.getString(4);
                String id = rows.getString(5);
                failureEvent = id == null ? null : UUID.fromString(id);
            }
        }
        long pending = 0;
        long quarantined = 0;
        Instant oldest = null;
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT delivery_status,COUNT(*),MIN(created_at) FROM website_export_events WHERE delivery_status<>'DELIVERED' GROUP BY delivery_status")) {
            while (rows.next()) {
                if ("PENDING".equals(rows.getString(1))) { pending = rows.getLong(2); oldest = instant(rows.getString(3)); }
                else if ("QUARANTINED".equals(rows.getString(1))) quarantined = rows.getLong(2);
            }
        }
        return new Status(source, pending, quarantined, oldest, acknowledged, failure, backfill, failureEvent);
    }

    void startBackfill() throws SQLException {
        requireEnabled();
        try (var statement = connection.createStatement()) {
            // Reissuing while active resumes the durable cursor instead of starting over.
            statement.executeUpdate("UPDATE website_export_state SET backfill_active=1,backfill_cursor=NULL WHERE id=1 AND backfill_active=0");
        }
    }

    int backfillBatch(int limit, Instant now) throws SQLException {
        if (!enabled) return 0;
        String cursor;
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT backfill_active,backfill_cursor FROM website_export_state WHERE id=1" + (mysql ? " FOR UPDATE" : ""))) {
            if (!rows.next() || rows.getInt(1) == 0) return 0;
            cursor = rows.getString(2);
        }
        var sessions = new ArrayList<UUID>();
        try (var statement = connection.prepareStatement("SELECT session_id FROM sessions WHERE session_id>? ORDER BY session_id LIMIT ?")) {
            statement.setString(1, cursor == null ? "" : cursor);
            statement.setInt(2, Math.clamp(limit, 1, 100));
            try (var rows = statement.executeQuery()) { while (rows.next()) sessions.add(UUID.fromString(rows.getString(1))); }
        }
        for (UUID session : sessions) enqueue(session, now);
        if (sessions.isEmpty()) {
            try (var statement = connection.createStatement()) { statement.executeUpdate("UPDATE website_export_state SET backfill_active=0 WHERE id=1"); }
        } else {
            try (var statement = connection.prepareStatement("UPDATE website_export_state SET backfill_cursor=? WHERE id=1")) {
                statement.setString(1, sessions.getLast().toString());
                statement.executeUpdate();
            }
        }
        return sessions.size();
    }

    private UUID source(boolean lock) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT source_id FROM website_export_state WHERE id=1" + (lock && mysql ? " FOR UPDATE" : ""))) {
            return rows.next() ? UUID.fromString(rows.getString(1)) : null;
        }
    }

    private void requireEnabled() {
        if (!enabled) throw new IllegalStateException("Website export must be enabled before backfill");
    }

    private void recordFailure(UUID eventId, String code) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE website_export_state SET last_failure=?,last_failure_event_id=? WHERE id=1")) {
            statement.setString(1, safeCode(code));
            statement.setString(2, eventId.toString());
            statement.executeUpdate();
        }
    }

    private static String safeCode(String code) { return code != null && code.matches("[A-Z][A-Z0-9_]{0,63}") ? code : "EXPORT_FAILURE"; }
    private static String time(Instant instant) { return UTC.format(instant); }
    private static Instant instant(String value) { return value == null ? null : Instant.parse(value); }

    public record Event(UUID eventId, UUID sessionId, long revision, byte[] payload, int attempts) {
        public Event { payload = payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }

    public record Status(UUID sourceId, long pending, long quarantined, Instant oldestPendingAt,
            Instant lastAcknowledgedAt, String lastFailure, boolean backfillActive, UUID lastFailureEventId) { }
}
