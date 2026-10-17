CREATE TABLE website_export_state (
 id INTEGER PRIMARY KEY CHECK(id=1), source_id TEXT NOT NULL,
 backfill_active INTEGER NOT NULL DEFAULT 0, backfill_cursor TEXT,
 last_acknowledged_at TEXT, last_failure TEXT, last_failure_event_id TEXT
);
CREATE TABLE website_match_revisions (
 session_id TEXT PRIMARY KEY REFERENCES sessions(session_id), revision INTEGER NOT NULL
);
CREATE TABLE website_export_events (
 event_id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(session_id), revision INTEGER NOT NULL,
 payload BLOB, created_at TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
 next_attempt_at TEXT NOT NULL, delivery_status TEXT NOT NULL,
 failure_code TEXT, acknowledged_at TEXT, stored_revision INTEGER,
 UNIQUE(session_id,revision), CHECK(delivery_status IN ('PENDING','DELIVERED','QUARANTINED'))
);
CREATE INDEX website_export_due ON website_export_events(delivery_status,next_attempt_at,created_at,session_id,revision);
