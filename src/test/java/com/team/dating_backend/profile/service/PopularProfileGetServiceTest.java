package com.team.dating_backend.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.matching.repository.LikeRepository;
import com.team.dating_backend.profile.dto.ProfileImageAccessResult;
import com.team.dating_backend.profile.repository.PopularProfileRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class PopularProfileGetServiceTest {

    private final LikeRepository likes = mock(LikeRepository.class);
    private final ProfileImageGetService images = mock(ProfileImageGetService.class);
    private final PopularProfileGetService service = new PopularProfileGetService(likes, images);

    @Test
    void 순위_순서와_지역_대표이미지를_반환한다() {
        Instant expires = Instant.parse("2026-01-01T00:00:00Z");
        given(likes.findPopularProfiles(any())).willReturn(List.of(
            new PopularProfileRow(2L, "둘", "서울", "강남", 3L),
            new PopularProfileRow(1L, "하나", null, null, 1L)));
        given(images.getProfileImagesByUserIds(List.of(2L, 1L))).willReturn(Map.of(
            2L, List.of(new ProfileImageAccessResult(9L, (short) 1, "https://example.com/9", expires))));

        var result = service.getPopularProfiles().profiles();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).rank()).isEqualTo(1);
        assertThat(result.get(0).userId()).isEqualTo(2L);
        assertThat(result.get(0).region()).isEqualTo("서울 강남");
        assertThat(result.get(0).likeCount()).isEqualTo(3L);
        assertThat(result.get(0).thumbnail().fileId()).isEqualTo(9L);
        assertThat(result.get(0).thumbnail().expiresAt()).isEqualTo(expires);
        assertThat(result.get(1).rank()).isEqualTo(2);
        assertThat(result.get(1).region()).isNull();
        assertThat(result.get(1).likeCount()).isEqualTo(1L);
        assertThat(result.get(1).thumbnail()).isNull();
        verify(likes).findPopularProfiles(PageRequest.of(0, 10));
    }
}
