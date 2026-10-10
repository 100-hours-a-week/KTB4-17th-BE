package com.team.dating_backend.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatRoomEndReason;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import com.team.dating_backend.chat.service.ChatRoomBlockTerminationService;
import com.team.dating_backend.matching.entity.Match;
import com.team.dating_backend.matching.enums.MatchEndReason;
import com.team.dating_backend.matching.enums.MatchStatus;
import com.team.dating_backend.matching.repository.MatchRepository;
import com.team.dating_backend.matching.service.MatchBlockTerminationService;
import com.team.dating_backend.user.dto.response.UserBlockResult;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.repository.UserBlockRepository;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@DataJpaTest(
    properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.flyway.enabled=false"
    }
)
@Import(
    {
        UserBlockService.class,
        MatchBlockTerminationService.class,
        ChatRoomBlockTerminationService.class
    }
)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserBlockIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private UserBlockService service;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserBlockRepository userBlockRepository;

    @Autowired
    private MatchRepository matchRepository;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @MockitoSpyBean
    private ChatRoomBlockTerminationService chatRoomBlockTerminationService;

    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private Long blockerId;
    private Long targetId;

    @BeforeEach
    void setUp() {
        chatRoomRepository.deleteAll();
        matchRepository.deleteAll();
        userBlockRepository.deleteAll();
        userRepository.deleteAll();
        blockerId = activeUser("차단회원").getId();
        targetId = activeUser("대상회원").getId();
    }

    @Test
    void 신규_차단과_재요청은_활성_Match와_Chat을_같은_시각으로_종료한다() {
        Relation firstRelation = activeRelation();

        UserBlockResult created = service.block(blockerId, targetId);

        assertThat(created.created()).isTrue();
        assertEnded(firstRelation, created.response().blockedAt());

        Relation remainingRelation = activeRelation();
        UserBlockResult retried = service.block(blockerId, targetId);

        assertThat(retried.created()).isFalse();
        assertThat(retried.response().blockedAt()).isEqualTo(created.response().blockedAt());
        assertEndedAtSameTime(remainingRelation);
        assertThat(userBlockRepository.count()).isEqualTo(1);
    }

    @Test
    void Chat_종료에_실패하면_차단_생성과_Match_종료를_함께_롤백한다() {
        Relation relation = activeRelation();
        ChatRoomBlockTerminationService terminationTarget = AopTestUtils
            .getUltimateTargetObject(chatRoomBlockTerminationService);
        doThrow(new IllegalStateException("chat termination failed"))
            .when(terminationTarget)
            .terminateBetween(any(), any(), any());

        assertThatThrownBy(() -> service.block(blockerId, targetId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("chat termination failed");

        assertThat(userBlockRepository.count()).isZero();
        Match memberMatch = matchRepository.findById(relation.matchId()).orElseThrow();
        ChatRoom room = chatRoomRepository.findById(relation.chatRoomId()).orElseThrow();
        assertThat(memberMatch.getStatus()).isEqualTo(MatchStatus.ACTIVE);
        assertThat(memberMatch.getEndReason()).isNull();
        assertThat(memberMatch.getEndedAt()).isNull();
        assertThat(room.getStatus()).isEqualTo(ChatRoomStatus.ACTIVE);
        assertThat(room.getEndReason()).isNull();
        assertThat(room.getEndedAt()).isNull();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void 동일_방향_차단을_동시에_요청해도_DB에_한_행만_생성하고_같은_시각을_반환한다()
        throws Exception {
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<UserBlockResult>> requests = java.util.stream.IntStream.range(0, 3)
            .mapToObj(index -> CompletableFuture.supplyAsync(() -> {
                ready.countDown();
                await(start);
                return service.block(blockerId, targetId);
            }, executor))
            .toList();
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<UserBlockResult> results = requests.stream()
            .map(request -> request.orTimeout(10, TimeUnit.SECONDS).join())
            .toList();

        assertThat(results).extracting(UserBlockResult::created)
            .containsExactlyInAnyOrder(true, false, false);
        assertThat(results).extracting(result -> result.response().blockedAt())
            .containsOnly(results.getFirst().response().blockedAt());
        assertThat(userBlockRepository.count()).isEqualTo(1);
    }

    @Test
    void 반대_방향_차단을_동시에_요청해도_Match와_Chat의_종료_정보는_한_번만_기록된다()
        throws Exception {
        Relation relation = activeRelation();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<UserBlockResult>> requests = List.of(
            blockAsync(blockerId, targetId, ready, start),
            blockAsync(targetId, blockerId, ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<UserBlockResult> results = requests.stream()
            .map(request -> request.orTimeout(10, TimeUnit.SECONDS).join())
            .toList();

        assertThat(results).extracting(UserBlockResult::created).containsOnly(true);
        assertThat(userBlockRepository.count()).isEqualTo(2);
        Match endedMatch = matchRepository.findById(relation.matchId()).orElseThrow();
        ChatRoom endedRoom = chatRoomRepository.findById(relation.chatRoomId()).orElseThrow();
        assertThat(endedMatch.getStatus()).isEqualTo(MatchStatus.ENDED);
        assertThat(endedMatch.getEndReason()).isEqualTo(MatchEndReason.BLOCKED);
        assertThat(endedRoom.getStatus()).isEqualTo(ChatRoomStatus.ENDED);
        assertThat(endedRoom.getEndReason()).isEqualTo(ChatRoomEndReason.BLOCKED);
        assertThat(endedRoom.getEndedAt()).isEqualTo(endedMatch.getEndedAt());
        assertThat(results).extracting(result -> result.response().blockedAt())
            .contains(endedMatch.getEndedAt());
    }

    private CompletableFuture<UserBlockResult> blockAsync(
        Long requesterId,
        Long blockedId,
        CountDownLatch ready,
        CountDownLatch start) {
        return CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            await(start);
            return service.block(requesterId, blockedId);
        }, executor);
    }

    private Relation activeRelation() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 1, 11, 0);
        Match memberMatch = matchRepository.saveAndFlush(new Match(blockerId, targetId, now));
        ChatRoom room = chatRoomRepository.saveAndFlush(new ChatRoom(memberMatch, now));
        return new Relation(memberMatch.getId(), room.getId());
    }

    private void assertEnded(Relation relation, LocalDateTime expectedEndedAt) {
        Match memberMatch = matchRepository.findById(relation.matchId()).orElseThrow();
        ChatRoom room = chatRoomRepository.findById(relation.chatRoomId()).orElseThrow();
        assertThat(memberMatch.getStatus()).isEqualTo(MatchStatus.ENDED);
        assertThat(memberMatch.getEndReason()).isEqualTo(MatchEndReason.BLOCKED);
        assertThat(memberMatch.getEndedAt()).isEqualTo(expectedEndedAt);
        assertThat(room.getStatus()).isEqualTo(ChatRoomStatus.ENDED);
        assertThat(room.getEndReason()).isEqualTo(ChatRoomEndReason.BLOCKED);
        assertThat(room.getEndedAt()).isEqualTo(expectedEndedAt);
    }

    private void assertEndedAtSameTime(Relation relation) {
        Match memberMatch = matchRepository.findById(relation.matchId()).orElseThrow();
        ChatRoom room = chatRoomRepository.findById(relation.chatRoomId()).orElseThrow();
        assertThat(memberMatch.getStatus()).isEqualTo(MatchStatus.ENDED);
        assertThat(memberMatch.getEndReason()).isEqualTo(MatchEndReason.BLOCKED);
        assertThat(room.getStatus()).isEqualTo(ChatRoomStatus.ENDED);
        assertThat(room.getEndReason()).isEqualTo(ChatRoomEndReason.BLOCKED);
        assertThat(room.getEndedAt()).isEqualTo(memberMatch.getEndedAt());
    }

    private User activeUser(String name) {
        LocalDateTime now = LocalDateTime.of(2026, 10, 1, 10, 0);
        User user = User.create(name, LocalDate.of(1995, 1, 1), Gender.MALE, now);
        user.activate(now);
        return userRepository.saveAndFlush(user);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent block start timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent block interrupted", exception);
        }
    }

    private record Relation(Long matchId, Long chatRoomId) {}
}
