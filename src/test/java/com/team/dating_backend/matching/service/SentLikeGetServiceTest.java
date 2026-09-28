package com.team.dating_backend.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.matching.dto.response.SentLikesGetResponse;
import com.team.dating_backend.matching.enums.LikeErrorCode;
import com.team.dating_backend.matching.enums.LikeStatus;
import com.team.dating_backend.matching.exception.LikeBusinessException;
import com.team.dating_backend.matching.repository.LikeRepository;
import com.team.dating_backend.matching.repository.SentLikeItem;
import com.team.dating_backend.profile.dto.ProfileImageAccessResult;
import com.team.dating_backend.profile.service.ProfileImageGetService;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class SentLikeGetServiceTest {

    private static final Long SENDER_ID = 1L;

    private UserRepository userRepository;
    private LikeRepository likeRepository;
    private ProfileImageGetService profileImageGetService;
    private SentLikeGetService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        likeRepository = mock(LikeRepository.class);
        profileImageGetService = mock(ProfileImageGetService.class);
        service = new SentLikeGetService(
            userRepository, likeRepository, profileImageGetService);
        given(profileImageGetService.getProfileImagesByMemberIds(
            org.mockito.ArgumentMatchers.anyCollection()))
            .willReturn(Map.of());
    }

    @Test
    void 최근에_보낸_좋아요_20개와_다음_커서를_반환한다() {
        givenActiveSender();
        List<SentLikeItem> rows = LongStream.rangeClosed(0, 20)
            .mapToObj(index -> row(121L - index, 201L + index))
            .toList();
        given(likeRepository.findSentPendingLikes(
            SENDER_ID, null, PageRequest.of(0, 21)))
            .willReturn(rows);

        SentLikesGetResponse response = service.getSentLikes(SENDER_ID, null);

        assertThat(response.items()).hasSize(20);
        assertThat(response.items().getFirst().likeId()).isEqualTo(121L);
        assertThat(response.items().getLast().likeId()).isEqualTo(102L);
        assertThat(response.pageInfo().nextCursor()).isEqualTo(102L);
        assertThat(response.pageInfo().hasNext()).isTrue();
        verify(profileImageGetService).getProfileImagesByMemberIds(
            LongStream.rangeClosed(201L, 220L).boxed().toList());
    }

    @Test
    void 상대_정보와_대표사진을_응답으로_변환한다() {
        givenActiveSender();
        LocalDate birthDate = LocalDate.now().minusYears(30).plusDays(1);
        SentLikeItem row = new SentLikeItem(
            101L,
            20L,
            birthDate,
            "하리",
            "개발자",
            "서울특별시",
            "강남구",
            LikeStatus.PENDING,
            LocalDateTime.of(2026, 9, 27, 12, 30));
        given(likeRepository.findSentPendingLikes(
            SENDER_ID, 120L, PageRequest.of(0, 21)))
            .willReturn(List.of(row));
        given(profileImageGetService.getProfileImagesByMemberIds(List.of(20L)))
            .willReturn(Map.of(
                20L,
                List.of(
                    new ProfileImageAccessResult(
                        702L, (short) 2, "https://example.com/second"),
                    new ProfileImageAccessResult(
                        701L, (short) 1, "https://example.com/representative"))));

        SentLikesGetResponse response = service.getSentLikes(SENDER_ID, 120L);

        var item = response.items().getFirst();
        assertThat(item.likeId()).isEqualTo(101L);
        assertThat(item.status()).isEqualTo(LikeStatus.PENDING);
        assertThat(item.createdAt()).isEqualTo(LocalDateTime.of(2026, 9, 27, 12, 30));
        assertThat(item.receiver().memberId()).isEqualTo(20L);
        assertThat(item.receiver().nickname()).isEqualTo("하리");
        assertThat(item.receiver().profileImageUrl())
            .isEqualTo("https://example.com/representative");
        assertThat(item.receiver().age()).isEqualTo(29);
        assertThat(item.receiver().job()).isEqualTo("개발자");
        assertThat(item.receiver().region()).isEqualTo("서울특별시 강남구");
        assertThat(response.pageInfo().nextCursor()).isNull();
        assertThat(response.pageInfo().hasNext()).isFalse();
    }

    @Test
    void 보낸_좋아요가_없으면_빈_목록을_반환한다() {
        givenActiveSender();
        given(likeRepository.findSentPendingLikes(
            SENDER_ID, null, PageRequest.of(0, 21)))
            .willReturn(List.of());

        SentLikesGetResponse response = service.getSentLikes(SENDER_ID, null);

        assertThat(response.items()).isEmpty();
        assertThat(response.pageInfo().nextCursor()).isNull();
        assertThat(response.pageInfo().hasNext()).isFalse();
        verify(profileImageGetService).getProfileImagesByMemberIds(List.of());
    }

    @Test
    void 비활성_사용자는_보낸_좋아요를_조회할_수_없다() {
        User sender = mock(User.class);
        given(sender.getStatus()).willReturn(UserStatus.SUSPENDED);
        given(userRepository.findById(SENDER_ID)).willReturn(Optional.of(sender));

        assertThatThrownBy(() -> service.getSentLikes(SENDER_ID, null))
            .isInstanceOf(LikeBusinessException.class)
            .satisfies(exception -> assertThat(
                ((LikeBusinessException) exception).getErrorCode())
                .isEqualTo(LikeErrorCode.SENDER_NOT_ACTIVE));
        verifyNoInteractions(likeRepository, profileImageGetService);
    }

    @Test
    void 양수가_아닌_커서는_거부한다() {
        assertThatThrownBy(() -> service.getSentLikes(SENDER_ID, 0L))
            .isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> service.getSentLikes(SENDER_ID, -1L))
            .isInstanceOf(RequestValidationException.class);
        verifyNoInteractions(userRepository, likeRepository, profileImageGetService);
    }

    private void givenActiveSender() {
        User sender = mock(User.class);
        given(sender.getStatus()).willReturn(UserStatus.ACTIVE);
        given(userRepository.findById(SENDER_ID)).willReturn(Optional.of(sender));
    }

    private SentLikeItem row(Long likeId, Long memberId) {
        return new SentLikeItem(
            likeId,
            memberId,
            LocalDate.of(1999, 1, 1),
            "하리",
            "개발자",
            "서울특별시",
            "강남구",
            LikeStatus.PENDING,
            LocalDateTime.of(2026, 9, 27, 12, 30));
    }
}
