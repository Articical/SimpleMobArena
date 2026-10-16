ALTER TABLE sessions ADD COLUMN end_reason VARCHAR(64) NOT NULL DEFAULT 'COMPLETED';
UPDATE sessions SET end_reason='CANCELLED' WHERE cancelled=1;
ALTER TABLE participants ADD COLUMN player_name VARCHAR(64) NOT NULL DEFAULT '';
UPDATE participants SET player_name=player_id WHERE player_name='';
