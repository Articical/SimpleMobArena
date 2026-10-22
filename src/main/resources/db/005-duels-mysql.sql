CREATE TABLE IF NOT EXISTS duels (
 session_id VARCHAR(36) PRIMARY KEY, arena_id VARCHAR(128) NOT NULL,
 mode VARCHAR(16) NOT NULL CHECK(mode IN ('DUEL','TEAM_DUEL')),
 started_at VARCHAR(40) NOT NULL, finished_at VARCHAR(40) NOT NULL,
 winner VARCHAR(4) NOT NULL CHECK(winner IN ('RED','BLUE','DRAW')),
 cancelled INTEGER NOT NULL CHECK(cancelled IN (0,1)), end_reason VARCHAR(64) NOT NULL,
 INDEX duels_finished(finished_at,session_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS duel_participants (
 session_id VARCHAR(36) NOT NULL, player_id VARCHAR(36) NOT NULL,
 player_name VARCHAR(64) NOT NULL, team VARCHAR(4) NOT NULL CHECK(team IN ('RED','BLUE')),
 kills INTEGER NOT NULL CHECK(kills >= 0), damage DOUBLE NOT NULL CHECK(damage >= 0),
 participation_seconds BIGINT NOT NULL CHECK(participation_seconds >= 0),
 eliminated INTEGER NOT NULL CHECK(eliminated IN (0,1)),
 PRIMARY KEY(session_id,player_id), FOREIGN KEY(session_id) REFERENCES duels(session_id),
 INDEX duel_participants_player(player_id,session_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS website_duel_export_state (
 id INTEGER PRIMARY KEY, source_id VARCHAR(36) NOT NULL,
 backfill_active INTEGER NOT NULL DEFAULT 0, backfill_cursor VARCHAR(36),
 last_acknowledged_at VARCHAR(40), last_failure VARCHAR(64), last_failure_event_id VARCHAR(36), CHECK(id=1)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS website_duel_revisions (
 session_id VARCHAR(36) PRIMARY KEY, revision BIGINT NOT NULL,
 FOREIGN KEY(session_id) REFERENCES duels(session_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS website_duel_export_events (
 event_id VARCHAR(36) PRIMARY KEY, session_id VARCHAR(36) NOT NULL, revision BIGINT NOT NULL,
 payload MEDIUMBLOB, created_at VARCHAR(40) NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
 next_attempt_at VARCHAR(40) NOT NULL, delivery_status VARCHAR(16) NOT NULL,
 failure_code VARCHAR(64), acknowledged_at VARCHAR(40), stored_revision BIGINT,
 UNIQUE(session_id,revision), FOREIGN KEY(session_id) REFERENCES duels(session_id),
 CHECK(delivery_status IN ('PENDING','DELIVERED','QUARANTINED')),
 INDEX website_duel_export_due(delivery_status,next_attempt_at,created_at,session_id,revision)
) ENGINE=InnoDB;

