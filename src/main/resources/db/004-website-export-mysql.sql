CREATE TABLE IF NOT EXISTS website_export_state (
 id INTEGER PRIMARY KEY, source_id VARCHAR(36) NOT NULL,
 backfill_active INTEGER NOT NULL DEFAULT 0, backfill_cursor VARCHAR(36),
 last_acknowledged_at VARCHAR(40), last_failure VARCHAR(64), last_failure_event_id VARCHAR(36), CHECK(id=1)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS website_match_revisions (
 session_id VARCHAR(36) PRIMARY KEY, revision BIGINT NOT NULL,
 FOREIGN KEY(session_id) REFERENCES sessions(session_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS website_export_events (
 event_id VARCHAR(36) PRIMARY KEY, session_id VARCHAR(36) NOT NULL, revision BIGINT NOT NULL,
 payload MEDIUMBLOB, created_at VARCHAR(40) NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
 next_attempt_at VARCHAR(40) NOT NULL, delivery_status VARCHAR(16) NOT NULL,
 failure_code VARCHAR(64), acknowledged_at VARCHAR(40), stored_revision BIGINT,
 UNIQUE(session_id,revision), FOREIGN KEY(session_id) REFERENCES sessions(session_id),
 CHECK(delivery_status IN ('PENDING','DELIVERED','QUARANTINED')),
 INDEX website_export_due(delivery_status,next_attempt_at,created_at,session_id,revision)
) ENGINE=InnoDB;
