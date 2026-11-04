package com.team.dating_backend.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatRoomEndReason;
import com.team.dating_backend.matching.entity.Like;
import com.team.dating_backend.matching.entity.Match;
import com.team.dating_backend.matching.enums.LikeStatus;
import com.team.dating_backend.matching.enums.MatchEndReason;
import com.team.dating_backend.matching.enums.MatchStatus;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.enums.ProfileImageErrorCode;
import com.team.dating_backend.profile.exception.ProfileImageBusinessException;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.entity.UserBlock;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.enums.UserStatus;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@Import({UserProfileGetService.class, ProfileAvailabilityValidator.class})
@Testcontainers
class UserProfileGetServiceIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 12, 0);

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private UserProfileGetService service;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private ProfileImageGetService profileImageGetService;

    private User viewer;
    private User target;
    private Profile targetProfile;

    @BeforeEach
    void setUp() {
        viewer = activeUser();
        profile(viewer);
        target = activeUser();
        targetProfile = profile(target);
        given(profileImageGetService.getProfileImagesByUserIds(List.of(target.getId())))
            .willReturn(Map.of());
    }

    @Test
    void 관계와_좋아요가_없는_랭킹_외_활성회원도_조회한다() {
        assertTargetAvailable();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 어느_방향의_차단_관계가_있어도_조회한다(boolean viewerBlocksTarget) {
        entityManager.persist(new UserBlock(
            viewerBlocksTarget ? viewer.getId() : target.getId(),
            viewerBlocksTarget ? target.getId() : viewer.getId(), NOW));

        assertTargetAvailable();
    }

    @ParameterizedTest
    @EnumSource(value = LikeStatus.class, names = {"PENDING", "REJECTED"})
    void 보낸_좋아요의_대기나_거절_이력이_있어도_조회한다(LikeStatus status) {
        Like like = new Like(viewer.getId(), target.getId(), NOW);
        if (status == LikeStatus.REJECTED) {
            like.resolveLike(status, NOW);
        }
        entityManager.persist(like);

        assertTargetAvailable();
    }

    @ParameterizedTest
    @EnumSource(MatchStatus.class)
    void 현재나_과거의_매칭_기록이_있어도_조회한다(MatchStatus status) {
        Match userMatch = new Match(viewer.getId(), target.getId(), NOW);
        if (status == MatchStatus.ENDED) {
            userMatch.end(MatchEndReason.USER_LEFT_CHAT, NOW);
        }
        entityManager.persist(userMatch);

        assertTargetAvailable();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 나나_상대가_채팅방을_나간_이력이_있어도_조회한다(boolean viewerLeft) {
        Match userMatch = new Match(viewer.getId(), target.getId(), NOW);
        entityManager.persist(userMatch);
        ChatRoom room = new ChatRoom(userMatch, NOW);
        entityManager.persist(room);
        ChatParticipant viewerParticipant = room.addParticipant(viewer.getId());
        ChatParticipant targetParticipant = room.addParticipant(target.getId());
        entityManager.persist(viewerParticipant);
        entityManager.persist(targetParticipant);
        (viewerLeft ? viewerParticipant : targetParticipant).leave(NOW);
        userMatch.end(MatchEndReason.USER_LEFT_CHAT, NOW);
        room.end(ChatRoomEndReason.USER_LEFT_CHAT, NOW);

        assertTargetAvailable();
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"ONBOARDING", "SUSPENDED", "WITHDRAWN"})
    void 비활성_회원은_정상_프로필이_있어도_거절한다(UserStatus status) {
        entityManager.flush();
        jdbc.update("UPDATE users SET status = ? WHERE id = ?", status.name(), target.getId());
        entityManager.clear();

        assertUnavailable(viewer.getId(), target.getId());
    }

    @Test
    void 삭제된_프로필은_활성회원이어도_거절한다() {
        targetProfile.markDeleted(NOW);
        entityManager.flush();
        entityManager.clear();

        assertUnavailable(viewer.getId(), target.getId());
    }

    @Test
    void 다른_정상_프로필이_있어도_요청한_회원의_프로필이_없으면_거절한다() {
        User withoutProfile = activeUser();
        entityManager.flush();
        entityManager.clear();

        assertUnavailable(viewer.getId(), withoutProfile.getId());
    }

    @Test
    void 존재하지_않는_대상은_거절한다() {
        assertUnavailable(viewer.getId(), Long.MAX_VALUE);
    }

    @Test
    void 본인의_활성_프로필도_동일한_검증을_거쳐_조회한다() {
        given(profileImageGetService.getProfileImagesByUserIds(List.of(viewer.getId())))
            .willReturn(Map.of());
        entityManager.flush();
        entityManager.clear();

        assertThat(service.getUserProfile(viewer.getId(), viewer.getId()).userId())
            .isEqualTo(viewer.getId());
    }

    @Test
    void 본인의_프로필도_비활성이면_거절한다() {
        entityManager.flush();
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", viewer.getId());
        entityManager.clear();

        assertUnavailable(viewer.getId(), viewer.getId());
    }

    private void assertTargetAvailable() {
        entityManager.flush();
        entityManager.clear();

        var response = service.getUserProfile(viewer.getId(), target.getId());

        assertThat(response.userId()).isEqualTo(target.getId());
        assertThat(response.nickname()).isEqualTo(targetProfile.getNickname());
        assertThat(response.images()).isEmpty();
    }

    private void assertUnavailable(Long viewerUserId, Long userId) {
        assertThatThrownBy(() -> service.getUserProfile(viewerUserId, userId))
            .isInstanceOf(ProfileImageBusinessException.class)
            .satisfies(exception -> assertThat(
                ((ProfileImageBusinessException) exception).getErrorCode())
                .isEqualTo(ProfileImageErrorCode.PROFILE_NOT_FOUND));
        verifyNoInteractions(profileImageGetService);
    }

    private User activeUser() {
        User user = User.create("회원", LocalDate.of(1996, 1, 1), Gender.MALE, NOW);
        user.activate(NOW);
        entityManager.persist(user);
        return user;
    }

    private Profile profile(User user) {
        Profile profile = new Profile(user);
        profile.updateProfile(null, "회원" + user.getId(), null, null, null, null,
            null, null, null, null);
        entityManager.persist(profile);
        return profile;
    }
}
