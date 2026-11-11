CREATE TABLE IF NOT EXISTS participant_mob_kills (
 session_id TEXT NOT NULL, player_id TEXT NOT NULL, mob_type TEXT NOT NULL,
 kills INTEGER NOT NULL CHECK(kills >= 0),
 PRIMARY KEY(session_id,player_id,mob_type),
 FOREIGN KEY(session_id,player_id) REFERENCES participants(session_id,player_id)
);
CREATE INDEX IF NOT EXISTS participant_mob_kills_player ON participant_mob_kills(player_id,mob_type);
CREATE TABLE IF NOT EXISTS live_mob_kills (
 session_id TEXT NOT NULL, player_id TEXT NOT NULL, mob_type TEXT NOT NULL,
 player_name TEXT NOT NULL, kills INTEGER NOT NULL CHECK(kills >= 0), updated_at TEXT NOT NULL,
 PRIMARY KEY(session_id,player_id,mob_type)
);
CREATE INDEX IF NOT EXISTS live_mob_kills_player ON live_mob_kills(player_id,mob_type);
