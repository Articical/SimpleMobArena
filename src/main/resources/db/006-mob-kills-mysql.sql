CREATE TABLE IF NOT EXISTS participant_mob_kills (
 session_id VARCHAR(36) NOT NULL, player_id VARCHAR(36) NOT NULL, mob_type VARCHAR(64) NOT NULL,
 kills INTEGER NOT NULL CHECK(kills >= 0),
 PRIMARY KEY(session_id,player_id,mob_type),
 FOREIGN KEY(session_id,player_id) REFERENCES participants(session_id,player_id),
 INDEX participant_mob_kills_player(player_id,mob_type)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS live_mob_kills (
 session_id VARCHAR(36) NOT NULL, player_id VARCHAR(36) NOT NULL, mob_type VARCHAR(64) NOT NULL,
 player_name VARCHAR(64) NOT NULL, kills INTEGER NOT NULL CHECK(kills >= 0), updated_at VARCHAR(40) NOT NULL,
 PRIMARY KEY(session_id,player_id,mob_type),
 INDEX live_mob_kills_player(player_id,mob_type)
) ENGINE=InnoDB;
