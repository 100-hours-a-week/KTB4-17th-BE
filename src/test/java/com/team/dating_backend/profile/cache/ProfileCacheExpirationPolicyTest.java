package com.team.dating_backend.profile.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.profile.config.ProfileCacheProperties;
import com.team.dating_backend.profile.dto.UserProfileLoadResult;
import com.team.dating_backend.profile.dto.response.UserProfileResponse;
import com.team.dating_backend.profile.enums.ProfileCacheErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProfileCacheExpirationPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-04T03:00:00Z");
    private SimpleMeterRegistry metrics;
    private ProfileCacheProperties properties;
    private ProfileCacheExpirationPolicy policy;

    @BeforeEach
    void setUp() {
        metrics = new SimpleMeterRegistry();
        properties = new ProfileCacheProperties();
        policy = new ProfileCacheExpirationPolicy(properties, metrics);
    }

    @AfterEach
    void tearDown() {
        metrics.close();
    }

    @Test
    void 이미지가_없으면_조회_시작으로부터_3분을_사용한다() {
        assertThat(policy.expiresAt(loaded(false, null))).contains(NOW.plusSeconds(180));
    }

    @Test
    void 이미지가_충분히_유효해도_최대_기한은_3분이다() {
        assertThat(policy.expiresAt(loaded(true, NOW.plusSeconds(600)))).contains(NOW.plusSeconds(180));
    }

    @Test
    void 이미지가_먼저_만료되면_10초의_요청_여유를_차감한다() {
        assertThat(policy.expiresAt(loaded(true, NOW.plusSeconds(120)))).contains(NOW.plusSeconds(110));
    }

    @Test
    void 남은_이미지_시간이_여유보다_짧으면_이미_지난_기한을_전달한다() {
        assertThat(policy.expiresAt(loaded(true, NOW.plusSeconds(5)))).contains(NOW.minusSeconds(5));
    }

    @Test
    void 이미지의_만료정보가_없으면_저장을_생략하고_오류코드를_기록한다() {
        assertThat(policy.expiresAt(loaded(true, null))).isEmpty();
        assertThat(metrics.get("profile.cache.fill.skipped")
            .tag("error_code", ProfileCacheErrorCode.PROFILE_CACHE_IMAGE_EXPIRATION_MISSING.name())
            .counter().count()).isEqualTo(1);
    }

    @Test
    void 외부_설정으로_TTL과_여유_시간을_변경할_수_있다() {
        properties.setMaxTtl(Duration.ofSeconds(60));
        properties.setImageRequestBuffer(Duration.ofSeconds(20));
        assertThat(policy.expiresAt(loaded(true, NOW.plusSeconds(70)))).contains(NOW.plusSeconds(50));
    }

    private UserProfileLoadResult loaded(boolean hasImage, Instant expiration) {
        UserProfileResponse response = new UserProfileResponse(21L, "회원", null, null, null, null,
            null, null, null, null, null, null,
            hasImage ? List.of(new UserProfileResponse.Image(701L, (short) 1, "https://example.com/701")) : List.of());
        return new UserProfileLoadResult(response, NOW, expiration);
    }
}
