UPDATE ai_simulation_sessions session
JOIN ai_simulation_reports report ON report.session_id = session.id
JOIN users target ON CAST(target.id AS CHAR) = JSON_UNQUOTE(JSON_EXTRACT(report.detail_result, '$.partner.user_id'))
SET session.target_user_id = target.id
WHERE session.target_user_id IS NULL
    AND JSON_UNQUOTE(JSON_EXTRACT(report.detail_result, '$.me.user_id')) = CAST(session.user_id AS CHAR)
    AND target.id <> session.user_id;

CREATE INDEX idx_ai_simulation_session_pair_id ON ai_simulation_sessions (user_id, target_user_id, id);
CREATE INDEX idx_ai_simulation_session_target_status_id ON ai_simulation_sessions (target_user_id, status, id);
CREATE INDEX idx_ai_simulation_session_status_created_id ON ai_simulation_sessions (status, created_at, id);
