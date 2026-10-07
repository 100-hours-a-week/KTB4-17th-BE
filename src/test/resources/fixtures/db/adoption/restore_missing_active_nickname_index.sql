-- 인덱스 누락 보정과 중복 데이터가 있을 때의 실패를 검증하는 테스트 전용 SQL이다.
CREATE UNIQUE INDEX uk_profiles_active_nickname
    ON profiles ((CASE WHEN deleted_at IS NULL THEN nickname ELSE NULL END));
