CREATE TABLE sessions (
 session_id VARCHAR(36) PRIMARY KEY, arena_id VARCHAR(128) NOT NULL,
 started_at VARCHAR(40) NOT NULL, finished_at VARCHAR(40) NOT NULL,
 completed_waves INTEGER NOT NULL, total_kills BIGINT NOT NULL, prize_pot DECIMAL(20,2) NOT NULL,
 cancelled INTEGER NOT NULL, settings TEXT NOT NULL, unallocated_pot DECIMAL(20,2) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE participants (
 session_id VARCHAR(36) NOT NULL, player_id VARCHAR(36) NOT NULL,
 kills INTEGER NOT NULL, damage DOUBLE NOT NULL, participation_seconds BIGINT NOT NULL,
 PRIMARY KEY(session_id, player_id),
 FOREIGN KEY(session_id) REFERENCES sessions(session_id)
) ENGINE=InnoDB;
CREATE TABLE settlements (
 settlement_id VARCHAR(36) PRIMARY KEY, session_id VARCHAR(36) NOT NULL,
 player_id VARCHAR(36) NOT NULL, share DECIMAL(30,16) NOT NULL,
 gross_amount DECIMAL(20,2) NOT NULL, intended_amount DECIMAL(20,2) NOT NULL,
 caps_applied TEXT NOT NULL, status VARCHAR(16) NOT NULL,
 reserved_at VARCHAR(40) NOT NULL, paid_at VARCHAR(40), paid_day VARCHAR(10), error TEXT,
 UNIQUE(session_id, player_id), FOREIGN KEY(session_id) REFERENCES sessions(session_id),
 CHECK(status IN ('PENDING','PROCESSING','CONFIRMED','UNCERTAIN','FAILED','WITHHELD','VOID'))
) ENGINE=InnoDB;
CREATE INDEX settlement_player_status ON settlements(player_id, status);
CREATE INDEX settlement_status ON settlements(status);
CREATE INDEX participants_player ON participants(player_id);
CREATE TABLE settlement_audit (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, settlement_id VARCHAR(36),
 event_at VARCHAR(40) NOT NULL, actor VARCHAR(255) NOT NULL, event VARCHAR(64) NOT NULL, detail TEXT NOT NULL,
 FOREIGN KEY(settlement_id) REFERENCES settlements(settlement_id)
) ENGINE=InnoDB;
