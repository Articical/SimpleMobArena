CREATE TABLE IF NOT EXISTS duels (
 session_id TEXT PRIMARY KEY, arena_id TEXT NOT NULL,
 mode TEXT NOT NULL CHECK(mode IN ('DUEL','TEAM_DUEL')),
 started_at TEXT NOT NULL, finished_at TEXT NOT NULL,
 winner TEXT NOT NULL CHECK(winner IN ('RED','BLUE','DRAW')),
 cancelled INTEGER NOT NULL CHECK(cancelled IN (0,1)), end_reason TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS duel_participants (
 session_id TEXT NOT NULL REFERENCES duels(session_id), player_id TEXT NOT NULL,
 player_name TEXT NOT NULL, team TEXT NOT NULL CHECK(team IN ('RED','BLUE')),
 kills INTEGER NOT NULL CHECK(kills >= 0), damage REAL NOT NULL CHECK(damage >= 0),
 participation_seconds INTEGER NOT NULL CHECK(participation_seconds >= 0),
 eliminated INTEGER NOT NULL CHECK(eliminated IN (0,1)),
 PRIMARY KEY(session_id,player_id)
);
CREATE INDEX IF NOT EXISTS duels_finished ON duels(finished_at,session_id);
CREATE INDEX IF NOT EXISTS duel_participants_player ON duel_participants(player_id,session_id);
CREATE TABLE IF NOT EXISTS website_duel_export_state (
 id INTEGER PRIMARY KEY CHECK(id=1), source_id TEXT NOT NULL,
 backfill_active INTEGER NOT NULL DEFAULT 0, backfill_cursor TEXT,
 last_acknowledged_at TEXT, last_failure TEXT, last_failure_event_id TEXT
);
CREATE TABLE IF NOT EXISTS website_duel_revisions (
 session_id TEXT PRIMARY KEY REFERENCES duels(session_id), revision INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS website_duel_export_events (
 event_id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES duels(session_id), revision INTEGER NOT NULL,
 payload BLOB, created_at TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
 next_attempt_at TEXT NOT NULL, delivery_status TEXT NOT NULL,
 failure_code TEXT, acknowledged_at TEXT, stored_revision INTEGER,
 UNIQUE(session_id,revision), CHECK(delivery_status IN ('PENDING','DELIVERED','QUARANTINED'))
);
CREATE INDEX IF NOT EXISTS website_duel_export_due ON website_duel_export_events(delivery_status,next_attempt_at,created_at,session_id,revision);

