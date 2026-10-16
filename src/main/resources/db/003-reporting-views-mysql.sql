CREATE VIEW mobarena_match_performance AS
SELECT p.player_id,p.player_name,s.session_id,s.arena_id,s.started_at,s.finished_at,s.completed_waves,s.cancelled,s.end_reason,
 s.total_kills AS match_total_kills,s.prize_pot,s.unallocated_pot,
 p.kills,p.damage,p.participation_seconds,t.share,t.gross_amount,t.intended_amount,
 CASE WHEN t.status='CONFIRMED' THEN t.intended_amount ELSE 0 END AS confirmed_amount,
 t.caps_applied,t.status,t.paid_at
FROM participants p JOIN sessions s ON s.session_id=p.session_id
JOIN settlements t ON t.session_id=p.session_id AND t.player_id=p.player_id;
CREATE VIEW mobarena_player_payout_history AS
SELECT player_id,player_name,session_id,arena_id,finished_at,share,gross_amount,intended_amount,
 confirmed_amount,caps_applied,status,paid_at FROM mobarena_match_performance;
CREATE VIEW mobarena_leaderboard AS
SELECT p.player_id,COUNT(*) AS total_sessions,SUM(p.kills) AS total_kills,SUM(p.damage) AS total_damage,
 MAX(s.completed_waves) AS highest_wave,
 COALESCE((SELECT SUM(CAST(t.intended_amount*100 AS SIGNED))
   FROM settlements t WHERE t.player_id=p.player_id AND t.status='CONFIRMED'),0) AS confirmed_earned_minor
FROM participants p JOIN sessions s ON s.session_id=p.session_id GROUP BY p.player_id;
