-- 기존 users 구조를 공통 스키마로 보정하는 동작을 검증하는 테스트 전용 SQL이다.
ALTER TABLE users
    MODIFY COLUMN name VARCHAR(8) NOT NULL AFTER last_accessed_at,
    MODIFY COLUMN persona_onboarding_status
        ENUM('BYPASSED', 'CONFIRMED', 'IN_PROGRESS', 'PENDING') NOT NULL;
