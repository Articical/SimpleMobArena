CREATE TABLE sessions (
 session_id TEXT PRIMARY KEY, arena_id TEXT NOT NULL,
 started_at TEXT NOT NULL, finished_at TEXT NOT NULL, completed_waves INTEGER NOT NULL CHECK(completed_waves >= 0),
 total_kills INTEGER NOT NULL CHECK(total_kills >= 0), prize_pot TEXT NOT NULL, cancelled INTEGER NOT NULL,
 settings TEXT NOT NULL, unallocated_pot TEXT NOT NULL
);
CREATE TABLE participants (
 session_id TEXT NOT NULL REFERENCES sessions(session_id), player_id TEXT NOT NULL,
 kills INTEGER NOT NULL CHECK(kills >= 0), damage REAL NOT NULL CHECK(damage >= 0),
 participation_seconds INTEGER NOT NULL CHECK(participation_seconds >= 0),
 PRIMARY KEY(session_id, player_id)
);
CREATE TABLE settlements (
 settlement_id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(session_id),
 player_id TEXT NOT NULL, share TEXT NOT NULL, gross_amount TEXT NOT NULL, intended_amount TEXT NOT NULL,
 caps_applied TEXT NOT NULL, status TEXT NOT NULL CHECK(status IN ('PENDING','PROCESSING','CONFIRMED','UNCERTAIN','FAILED','WITHHELD','VOID')),
 reserved_at TEXT NOT NULL, paid_at TEXT, paid_day TEXT, error TEXT,
 UNIQUE(session_id, player_id)
);
CREATE INDEX settlement_player_status ON settlements(player_id, status);
CREATE INDEX settlement_status ON settlements(status);
CREATE INDEX participants_player ON participants(player_id);
CREATE TABLE settlement_audit (
 id INTEGER PRIMARY KEY AUTOINCREMENT, settlement_id TEXT REFERENCES settlements(settlement_id),
 event_at TEXT NOT NULL, actor TEXT NOT NULL, event TEXT NOT NULL, detail TEXT NOT NULL
);
