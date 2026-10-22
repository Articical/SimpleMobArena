package cloud.mineplay.mobarena.storage;

import cloud.mineplay.mobarena.duels.DuelResult;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Uses the owning ledger's connection and transactions, exclusively on its worker. */
final class DuelStore {
    private static final int PAGE_SIZE = 10;
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    private final Connection connection;

    DuelStore(Connection connection) { this.connection = connection; }

    boolean record(DuelResult result) throws SQLException {
        DuelResult previous = match(result.sessionId());
        if (previous != null) {
            if (!previous.equals(result)) throw new SQLException("Conflicting duel result for session " + result.sessionId());
            return false;
        }
        try (var insert = connection.prepareStatement("INSERT INTO duels(session_id,arena_id,mode,started_at,finished_at,winner,cancelled,end_reason) VALUES(?,?,?,?,?,?,?,?)")) {
            insert.setString(1, result.sessionId().toString());
            insert.setString(2, result.arenaId());
            insert.setString(3, result.mode());
            insert.setString(4, TIMESTAMP.format(result.startedAt()));
            insert.setString(5, TIMESTAMP.format(result.finishedAt()));
            insert.setString(6, result.winner());
            insert.setInt(7, result.cancelled() ? 1 : 0);
            insert.setString(8, result.endReason());
            insert.executeUpdate();
        }
        try (var insert = connection.prepareStatement("INSERT INTO duel_participants(session_id,player_id,player_name,team,kills,damage,participation_seconds,eliminated) VALUES(?,?,?,?,?,?,?,?)")) {
            for (var participant : result.participants()) {
                insert.setString(1, result.sessionId().toString());
                insert.setString(2, participant.playerId().toString());
                insert.setString(3, participant.playerName());
                insert.setString(4, participant.team());
                insert.setInt(5, participant.kills());
                insert.setDouble(6, participant.damage());
                insert.setLong(7, participant.participationSeconds());
                insert.setInt(8, participant.eliminated() ? 1 : 0);
                insert.addBatch();
            }
            insert.executeBatch();
        }
        return true;
    }

    DuelResult match(UUID sessionId) throws SQLException {
        var participants = new ArrayList<DuelResult.Participant>();
        try (var query = connection.prepareStatement("SELECT * FROM duel_participants WHERE session_id=? ORDER BY team,player_id")) {
            query.setString(1, sessionId.toString());
            try (var rows = query.executeQuery()) {
                while (rows.next()) participants.add(new DuelResult.Participant(UUID.fromString(rows.getString("player_id")),
                        rows.getString("player_name"), rows.getString("team"), rows.getInt("kills"), rows.getDouble("damage"),
                        rows.getLong("participation_seconds"), rows.getInt("eliminated") != 0));
            }
        }
        try (var query = connection.prepareStatement("SELECT * FROM duels WHERE session_id=?")) {
            query.setString(1, sessionId.toString());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return null;
                return new DuelResult(sessionId, rows.getString("arena_id"), rows.getString("mode"),
                        Instant.parse(rows.getString("started_at")), Instant.parse(rows.getString("finished_at")),
                        rows.getString("winner"), rows.getInt("cancelled") != 0, rows.getString("end_reason"), participants);
            }
        }
    }

    List<DuelResult> history(UUID playerId, int page) throws SQLException {
        var sessions = new ArrayList<UUID>();
        try (var query = connection.prepareStatement("SELECT d.session_id FROM duels d JOIN duel_participants p ON d.session_id=p.session_id WHERE p.player_id=? ORDER BY d.finished_at DESC,d.session_id DESC LIMIT ? OFFSET ?")) {
            query.setString(1, playerId.toString());
            query.setInt(2, PAGE_SIZE);
            query.setLong(3, (Math.max(1L, page) - 1) * PAGE_SIZE);
            try (var rows = query.executeQuery()) {
                while (rows.next()) sessions.add(UUID.fromString(rows.getString(1)));
            }
        }
        var matches = new ArrayList<DuelResult>();
        for (UUID session : sessions) matches.add(match(session));
        return List.copyOf(matches);
    }
}
