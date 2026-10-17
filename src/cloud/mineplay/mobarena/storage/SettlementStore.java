package cloud.mineplay.mobarena.storage;

import cloud.mineplay.mobarena.economy.EconomySettings;
import cloud.mineplay.mobarena.economy.MatchDetails;
import cloud.mineplay.mobarena.economy.MatchPerformance;
import cloud.mineplay.mobarena.economy.PlayerStatistics;
import cloud.mineplay.mobarena.economy.RewardCalculator;
import cloud.mineplay.mobarena.economy.SessionResult;
import cloud.mineplay.mobarena.economy.Settlement;
import cloud.mineplay.mobarena.economy.Settlement.Status;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Single-writer SQLite ledger. No Bukkit API is used in this class. */
public final class SettlementStore implements AutoCloseable {
    private final Connection connection;
    private final boolean mysql;
    private final WebsiteExportStore website;

    public SettlementStore(Path database) throws SQLException, IOException {
        this(DatabaseSettings.sqlite(database.toAbsolutePath().getParent()), database.getFileName().toString());
    }

    public SettlementStore(DatabaseSettings settings) throws SQLException, IOException { this(settings, "mobarena.db"); }

    private SettlementStore(DatabaseSettings settings, String filename) throws SQLException, IOException {
        mysql = settings.mysql();
        connection = settings.connect(filename);
        try { migrate(); }
        catch (SQLException | IOException | RuntimeException exception) {
            try { connection.close(); } catch (SQLException close) { exception.addSuppressed(close); }
            throw exception;
        }
        website = new WebsiteExportStore(connection, mysql);
    }

    private void migrate() throws SQLException, IOException {
        int version = 0;
        if (mysql) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS mobarena_schema(component VARCHAR(32) PRIMARY KEY, version INTEGER NOT NULL) ENGINE=InnoDB");
                try (var rows = statement.executeQuery("SELECT version FROM mobarena_schema WHERE component='ledger'")) {
                    if (rows.next()) version = rows.getInt(1);
                }
            }
        } else {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA user_version")) {
                if (!rows.next()) throw new SQLException("Cannot read schema version");
                version = rows.getInt(1);
            }
        }
        if (version > 4) throw new SQLException("Ledger schema is newer than this plugin; refusing to downgrade");
        for (int next = version + 1; next <= 4; next++) {
            String resource = switch (next) {
                case 1 -> mysql ? "/db/001-mysql.sql" : "/db/001-initial.sql";
                case 2 -> "/db/002-match-details.sql";
                case 3 -> mysql ? "/db/003-reporting-views-mysql.sql" : "/db/003-reporting-views.sql";
                case 4 -> mysql ? "/db/004-website-export-mysql.sql" : "/db/004-website-export.sql";
                default -> throw new IllegalStateException();
            };
            String schema;
            try (var input = getClass().getResourceAsStream(resource)) {
                if (input == null) throw new IOException("Missing schema migration: " + resource);
                schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            final int targetVersion = next;
            transaction(() -> {
                try (var statement = connection.createStatement()) {
                    for (String sql : schema.split(";")) if (!sql.isBlank()) statement.execute(sql);
                    if (mysql) {
                        statement.executeUpdate("DELETE FROM mobarena_schema WHERE component='ledger'");
                        statement.executeUpdate("INSERT INTO mobarena_schema(component,version) VALUES('ledger'," + targetVersion + ")");
                    } else statement.execute("PRAGMA user_version=" + targetVersion);
                }
                return null;
            });
        }
    }

    /** A crash may have occurred after a deposit. Processing entries are never retried automatically. */
    public synchronized int recover(Instant now) throws SQLException {
        return transaction(() -> {
            var processing = byStatus(Status.PROCESSING, Integer.MAX_VALUE);
            for (var payment : processing) {
                updateStatus(payment.settlementId(), Status.UNCERTAIN, null, "Process stopped while payment was processing");
                audit(payment.settlementId(), now, "SYSTEM", "RECOVER_UNCERTAIN", "Do not retry; verify provider records before reconciliation");
            }
            for (UUID session : processing.stream().map(Settlement::sessionId).distinct().toList()) website.enqueue(session, now);
            return processing.size();
        });
    }

    /** Atomically reserves all participants' caps before any external payment can happen. */
    public synchronized boolean prepare(SessionResult result, EconomySettings settings, Instant now) throws SQLException {
        return transaction(() -> {
            try (var existing = connection.prepareStatement("SELECT 1 FROM sessions WHERE session_id=?")) {
                existing.setString(1, result.sessionId().toString());
                try (var rows = existing.executeQuery()) { if (rows.next()) return false; }
            }
            BigDecimal recordedPot = RewardCalculator.capPot(settings, result.prizePot());
            try (var statement = connection.prepareStatement("INSERT INTO sessions(session_id,arena_id,started_at,finished_at,completed_waves,total_kills,prize_pot,cancelled,settings,unallocated_pot,end_reason) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                statement.setString(1, result.sessionId().toString());
                statement.setString(2, result.arenaId());
                statement.setString(3, result.startedAt().toString());
                statement.setString(4, result.finishedAt().toString());
                statement.setInt(5, result.completedWaves());
                statement.setLong(6, result.participants().stream().mapToLong(SessionResult.Participant::kills).sum());
                statement.setString(7, recordedPot.toPlainString());
                statement.setInt(8, result.cancelled() ? 1 : 0);
                statement.setString(9, settings.toString());
                statement.setString(10, recordedPot.toPlainString());
                statement.setString(11, result.endReason());
                statement.executeUpdate();
            }
            var allocations = RewardCalculator.distribute(result, settings);
            BigDecimal allocated = BigDecimal.ZERO;
            for (var participant : result.participants()) {
                insertParticipant(result.sessionId(), participant);
                var allocation = allocations.get(participant.playerId());
                BigDecimal amount = allocation.intended();
                String caps = allocation.reason();
                if (amount.signum() > 0 && coolingDown(participant.playerId(), result.startedAt(), settings)) {
                    amount = BigDecimal.ZERO.setScale(settings.scale());
                    caps = append(caps, "REWARD_COOLDOWN");
                }
                if (amount.signum() > 0) {
                    BigDecimal available = settings.maximumDailyPayout().subtract(reservedAndPaidToday(participant.playerId(), now, null)).max(BigDecimal.ZERO);
                    if (amount.compareTo(available) > 0) {
                        amount = available.setScale(settings.scale(), RoundingMode.DOWN);
                        caps = append(caps, "DAILY_CAP");
                    }
                }
                var settlement = new Settlement(UUID.randomUUID(), result.sessionId(), participant.playerId(), allocation.share(),
                        allocation.gross(), amount, caps, amount.signum() > 0 ? Status.PENDING : Status.WITHHELD, null, null);
                insertSettlement(settlement, result.finishedAt());
                audit(settlement.settlementId(), now, "SYSTEM", "PREPARED", "amount=" + amount.toPlainString() + "; caps=" + caps);
                allocated = allocated.add(amount);
            }
            setUnallocated(result.sessionId(), recordedPot.subtract(allocated));
            website.enqueue(result.sessionId(), now);
            return true;
        });
    }

    /** Durable PROCESSING must be committed before the caller invokes Vault. */
    public synchronized Settlement claim(UUID settlementId, EconomySettings settings, Instant now) throws SQLException {
        return transaction(() -> {
            Settlement payment = get(settlementId);
            if (payment == null || payment.status() != Status.PENDING || !settings.enabled()) return null;
            BigDecimal available = settings.maximumDailyPayout().subtract(reservedAndPaidToday(payment.playerId(), now, settlementId)).max(BigDecimal.ZERO);
            BigDecimal amount = payment.intendedAmount().min(available).min(settings.maximumPlayerPayout()).setScale(settings.scale(), RoundingMode.DOWN);
            String caps = payment.capsApplied();
            if (amount.compareTo(payment.intendedAmount()) < 0) {
                caps = append(caps, "CAP_RECHECK_AT_PAYMENT");
                addUnallocated(payment.sessionId(), payment.intendedAmount().subtract(amount));
            }
            Status status = amount.signum() == 0 ? Status.WITHHELD : Status.PROCESSING;
            try (var statement = connection.prepareStatement("UPDATE settlements SET intended_amount=?,caps_applied=?,status=? WHERE settlement_id=? AND status='PENDING'")) {
                statement.setString(1, amount.toPlainString());
                statement.setString(2, caps);
                statement.setString(3, status.name());
                statement.setString(4, settlementId.toString());
                if (statement.executeUpdate() != 1) throw new SQLException("Settlement claim conflict");
            }
            audit(settlementId, now, "SYSTEM", status.name(), "amount=" + amount.toPlainString());
            website.enqueue(payment.sessionId(), now);
            return status == Status.PROCESSING ? get(settlementId) : null;
        });
    }

    public synchronized void complete(UUID id, Status status, String error, Instant now) throws SQLException {
        if (status != Status.CONFIRMED && status != Status.FAILED && status != Status.UNCERTAIN && status != Status.PENDING) {
            throw new IllegalArgumentException("Invalid payment completion status");
        }
        transaction(() -> {
            Settlement payment = get(id);
            if (payment == null || payment.status() != Status.PROCESSING) throw new SQLException("Payment is not processing");
            updateStatus(id, status, status == Status.CONFIRMED ? now : null, error);
            if (status == Status.FAILED) addUnallocated(payment.sessionId(), payment.intendedAmount());
            audit(id, now, "VAULT", status.name(), error == null ? "" : error);
            website.enqueue(payment.sessionId(), now);
            return null;
        });
    }

    public synchronized String reconcile(UUID id, String decision, String actor, Instant now) throws SQLException {
        return transaction(() -> {
            Settlement payment = get(id);
            if (payment == null) return "Unknown settlement.";
            if (payment.status() != Status.UNCERTAIN) return "Only UNCERTAIN settlements can be reconciled. Current status: " + payment.status();
            Status target;
            if ("CONFIRM".equalsIgnoreCase(decision)) target = Status.CONFIRMED;
            else if ("VOID".equalsIgnoreCase(decision)) target = Status.VOID;
            else return "Use CONFIRM after verifying the deposit exists, or VOID after verifying it did not occur. No money is sent by reconciliation.";
            updateStatus(id, target, target == Status.CONFIRMED ? now : null, "Administratively reconciled by " + actor);
            if (target == Status.VOID) addUnallocated(payment.sessionId(), payment.intendedAmount());
            audit(id, now, actor, "RECONCILE_" + target, "Operator has verified provider records; no new deposit was made");
            website.enqueue(payment.sessionId(), now);
            return "Settlement " + id + " recorded as " + target + ". No money was sent.";
        });
    }

    public synchronized List<Settlement> pending(int limit) throws SQLException { return byStatus(Status.PENDING, limit); }

    /** All facade methods are intended for the background ledger worker, never the Paper thread. */
    public synchronized void configureWebsiteExport(boolean enabled, UUID sourceId, Instant now) throws SQLException {
        transaction(() -> { website.configure(enabled, sourceId); return null; });
    }

    public synchronized List<WebsiteExportStore.Event> websiteEvents(Instant now, int limit) throws SQLException {
        return website.events(now, limit);
    }

    public synchronized void websiteDelivered(UUID eventId, long storedRevision, Instant now) throws SQLException {
        transaction(() -> { website.delivered(eventId, storedRevision, now); return null; });
    }

    public synchronized void websiteFailed(UUID eventId, String safeCode, Instant nextAttempt, boolean quarantine) throws SQLException {
        transaction(() -> { website.failed(eventId, safeCode, nextAttempt, quarantine); return null; });
    }

    public synchronized WebsiteExportStore.Status websiteStatus() throws SQLException { return website.status(); }

    public synchronized boolean websiteRetry(UUID eventId, Instant now) throws SQLException {
        return transaction(() -> website.retry(eventId, now));
    }

    public synchronized void websiteStartBackfill(Instant now) throws SQLException {
        transaction(() -> { website.startBackfill(); return null; });
    }

    public synchronized int websiteBackfillBatch(int limit, Instant now) throws SQLException {
        return transaction(() -> website.backfillBatch(limit, now));
    }

    public synchronized List<Settlement> payouts(UUID session) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM settlements WHERE session_id=? ORDER BY player_id")) {
            statement.setString(1, session.toString());
            return readSettlements(statement);
        }
    }

    public synchronized Settlement get(UUID id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM settlements WHERE settlement_id=?")) {
            statement.setString(1, id.toString());
            try (var rows = statement.executeQuery()) { return rows.next() ? readSettlement(rows) : null; }
        }
    }

    public synchronized PlayerStatistics stats(UUID player) throws SQLException {
        long sessions = 0, kills = 0;
        double damage = 0;
        int highest = 0;
        try (var statement = connection.prepareStatement("SELECT COUNT(*),COALESCE(SUM(p.kills),0),COALESCE(SUM(p.damage),0),COALESCE(MAX(s.completed_waves),0) FROM participants p JOIN sessions s ON p.session_id=s.session_id WHERE p.player_id=?")) {
            statement.setString(1, player.toString());
            try (var rows = statement.executeQuery()) {
                if (rows.next()) { sessions = rows.getLong(1); kills = rows.getLong(2); damage = rows.getDouble(3); highest = rows.getInt(4); }
            }
        }
        BigDecimal earned = BigDecimal.ZERO;
        try (var statement = connection.prepareStatement("SELECT intended_amount FROM settlements WHERE player_id=? AND status='CONFIRMED'")) {
            statement.setString(1, player.toString());
            try (var rows = statement.executeQuery()) { while (rows.next()) earned = earned.add(new BigDecimal(rows.getString(1))); }
        }
        String playerName = player.toString();
        try (var statement = connection.prepareStatement("SELECT p.player_name FROM participants p JOIN sessions s ON p.session_id=s.session_id WHERE p.player_id=? ORDER BY s.finished_at DESC,s.session_id DESC LIMIT 1")) {
            statement.setString(1, player.toString());
            try (var rows = statement.executeQuery()) { if (rows.next()) playerName = rows.getString(1); }
        }
        return new PlayerStatistics(player, sessions, kills, damage, highest, earned, playerName);
    }

    public synchronized List<PlayerStatistics> top(int limit) throws SQLException {
        var players = new ArrayList<UUID>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT DISTINCT player_id FROM participants")) {
            while (rows.next()) players.add(UUID.fromString(rows.getString(1)));
        }
        var result = new ArrayList<PlayerStatistics>();
        for (UUID player : players) result.add(stats(player));
        result.sort(Comparator.comparingLong(PlayerStatistics::kills).reversed()
                .thenComparing(Comparator.comparingInt(PlayerStatistics::highestWave).reversed()).thenComparing(p -> p.playerId().toString()));
        return List.copyOf(result.subList(0, Math.min(Math.max(0, limit), result.size())));
    }

    public synchronized List<MatchPerformance> history(UUID player, int page) throws SQLException {
        if (page < 1 || page > 1000000) throw new IllegalArgumentException("History page must be between 1 and 1000000");
        var result = new ArrayList<MatchPerformance>();
        try (var statement = connection.prepareStatement("SELECT s.session_id,s.arena_id,s.started_at,s.finished_at,s.completed_waves,s.cancelled,p.kills,p.damage,p.participation_seconds,t.* FROM participants p JOIN sessions s ON s.session_id=p.session_id JOIN settlements t ON t.session_id=p.session_id AND t.player_id=p.player_id WHERE p.player_id=? ORDER BY s.finished_at DESC,s.session_id LIMIT 10 OFFSET ?")) {
            statement.setString(1, player.toString()); statement.setInt(2, (page - 1) * 10);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    Settlement payment = readSettlement(rows);
                    result.add(new MatchPerformance(payment.sessionId(), rows.getString("arena_id"),
                            Instant.parse(rows.getString("started_at")), Instant.parse(rows.getString("finished_at")),
                            rows.getInt("completed_waves"), rows.getInt("cancelled") != 0, rows.getInt("kills"),
                            rows.getDouble("damage"), rows.getLong("participation_seconds"), payment.share(), payment.grossAmount(),
                            payment.intendedAmount(), payment.status() == Status.CONFIRMED ? payment.intendedAmount() : BigDecimal.ZERO,
                            payment.capsApplied(), payment.status(), payment.paidAt(), payment.settlementId()));
                }
            }
        }
        return List.copyOf(result);
    }

    public synchronized MatchDetails match(UUID sessionId) throws SQLException {
        var roster = new ArrayList<MatchDetails.MatchParticipant>();
        try (var statement = connection.prepareStatement("SELECT p.player_id,p.player_name,p.kills,p.damage,p.participation_seconds,t.* FROM participants p JOIN settlements t ON t.session_id=p.session_id AND t.player_id=p.player_id WHERE p.session_id=? ORDER BY p.kills DESC,p.player_id")) {
            statement.setString(1, sessionId.toString());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) roster.add(new MatchDetails.MatchParticipant(UUID.fromString(rows.getString("player_id")),
                        rows.getString("player_name"), rows.getInt("kills"), rows.getDouble("damage"),
                        rows.getLong("participation_seconds"), readSettlement(rows)));
            }
        }
        try (var statement = connection.prepareStatement("SELECT * FROM sessions WHERE session_id=?")) {
            statement.setString(1, sessionId.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new MatchDetails(sessionId, rows.getString("arena_id"), Instant.parse(rows.getString("started_at")),
                        Instant.parse(rows.getString("finished_at")), rows.getInt("completed_waves"), rows.getLong("total_kills"),
                        new BigDecimal(rows.getString("prize_pot")), new BigDecimal(rows.getString("unallocated_pot")),
                        rows.getInt("cancelled") != 0, rows.getString("end_reason"), roster);
            }
        }
    }

    /** Pending and uncertain holds count on every day until resolved, even after midnight. */
    private BigDecimal reservedAndPaidToday(UUID player, Instant now, UUID excluded) throws SQLException {
        BigDecimal total = BigDecimal.ZERO;
        try (var statement = connection.prepareStatement("SELECT settlement_id,intended_amount FROM settlements WHERE player_id=? AND (status IN ('PENDING','PROCESSING','UNCERTAIN') OR (status='CONFIRMED' AND paid_day=?))")) {
            statement.setString(1, player.toString());
            statement.setString(2, now.atZone(ZoneOffset.UTC).toLocalDate().toString());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (excluded == null || !excluded.toString().equals(rows.getString(1))) total = total.add(new BigDecimal(rows.getString(2)));
                }
            }
        }
        return total;
    }

    private boolean coolingDown(UUID player, Instant sessionStart, EconomySettings settings) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT reserved_at FROM settlements WHERE player_id=? AND status IN ('PENDING','PROCESSING','UNCERTAIN','CONFIRMED')")) {
            statement.setString(1, player.toString());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) if (sessionStart.isBefore(Instant.parse(rows.getString(1)).plus(settings.rewardCooldown()))) return true;
            }
        }
        return false;
    }

    private void insertParticipant(UUID session, SessionResult.Participant participant) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO participants(session_id,player_id,kills,damage,participation_seconds,player_name) VALUES(?,?,?,?,?,?)")) {
            statement.setString(1, session.toString()); statement.setString(2, participant.playerId().toString());
            statement.setInt(3, participant.kills()); statement.setDouble(4, participant.damage());
            statement.setLong(5, participant.participationSeconds()); statement.setString(6, participant.playerName()); statement.executeUpdate();
        }
    }

    private void insertSettlement(Settlement payment, Instant reservedAt) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO settlements(settlement_id,session_id,player_id,share,gross_amount,intended_amount,caps_applied,status,reserved_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, payment.settlementId().toString()); statement.setString(2, payment.sessionId().toString());
            statement.setString(3, payment.playerId().toString()); statement.setString(4, payment.share().toPlainString());
            statement.setString(5, payment.grossAmount().toPlainString()); statement.setString(6, payment.intendedAmount().toPlainString());
            statement.setString(7, payment.capsApplied()); statement.setString(8, payment.status().name());
            statement.setString(9, reservedAt.toString()); statement.executeUpdate();
        }
    }

    private List<Settlement> byStatus(Status status, int limit) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM settlements WHERE status=? ORDER BY reserved_at,settlement_id LIMIT ?")) {
            statement.setString(1, status.name()); statement.setInt(2, limit);
            return readSettlements(statement);
        }
    }

    private List<Settlement> readSettlements(PreparedStatement statement) throws SQLException {
        var result = new ArrayList<Settlement>();
        try (var rows = statement.executeQuery()) { while (rows.next()) result.add(readSettlement(rows)); }
        return List.copyOf(result);
    }

    private Settlement readSettlement(ResultSet rows) throws SQLException {
        String paidAt = rows.getString("paid_at");
        return new Settlement(UUID.fromString(rows.getString("settlement_id")), UUID.fromString(rows.getString("session_id")),
                UUID.fromString(rows.getString("player_id")), new BigDecimal(rows.getString("share")),
                new BigDecimal(rows.getString("gross_amount")), new BigDecimal(rows.getString("intended_amount")),
                rows.getString("caps_applied"), Status.valueOf(rows.getString("status")),
                paidAt == null ? null : Instant.parse(paidAt), rows.getString("error"));
    }

    private void updateStatus(UUID id, Status status, Instant paidAt, String error) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE settlements SET status=?,paid_at=?,paid_day=?,error=? WHERE settlement_id=?")) {
            statement.setString(1, status.name()); statement.setString(2, paidAt == null ? null : paidAt.toString());
            statement.setString(3, paidAt == null ? null : paidAt.atZone(ZoneOffset.UTC).toLocalDate().toString());
            statement.setString(4, error); statement.setString(5, id.toString()); statement.executeUpdate();
        }
    }

    private void audit(UUID id, Instant now, String actor, String event, String detail) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO settlement_audit(settlement_id,event_at,actor,event,detail) VALUES(?,?,?,?,?)")) {
            statement.setString(1, id.toString()); statement.setString(2, now.toString()); statement.setString(3, actor);
            statement.setString(4, event); statement.setString(5, detail); statement.executeUpdate();
        }
    }

    private void addUnallocated(UUID session, BigDecimal amount) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT unallocated_pot FROM sessions WHERE session_id=?")) {
            statement.setString(1, session.toString());
            try (var rows = statement.executeQuery()) { if (rows.next()) setUnallocated(session, new BigDecimal(rows.getString(1)).add(amount)); }
        }
    }

    private void setUnallocated(UUID session, BigDecimal amount) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE sessions SET unallocated_pot=? WHERE session_id=?")) {
            statement.setString(1, amount.toPlainString()); statement.setString(2, session.toString()); statement.executeUpdate();
        }
    }

    private static String append(String current, String reason) { return current.isEmpty() ? reason : current + "," + reason; }

    private <T> T transaction(SqlWork<T> work) throws SQLException {
        connection.setAutoCommit(false);
        try {
            T result = work.run();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException exception) {
            try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
            throw exception;
        } finally { connection.setAutoCommit(true); }
    }

    @Override public synchronized void close() throws SQLException { connection.close(); }
    @FunctionalInterface private interface SqlWork<T> { T run() throws SQLException; }
}
