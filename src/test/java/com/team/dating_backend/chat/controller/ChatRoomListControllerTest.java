package com.team.dating_backend.chat.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.team.dating_backend.chat.dto.read.ChatRoomCursor;
import com.team.dating_backend.chat.dto.read.ChatRoomPage;
import com.team.dating_backend.chat.dto.read.ChatRoomPageInfo;
import com.team.dating_backend.chat.dto.read.ChatRoomPreview;
import com.team.dating_backend.chat.dto.read.ChatRoomSummary;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.enums.ChatRoomPreviewType;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.service.ChatRoomListService;
import com.team.dating_backend.chat.service.ChatRoomParticipantDisplayService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@WebMvcTest(controllers = ChatRoomListController.class, excludeAutoConfiguration = {
    SecurityAutoConfiguration.class,
    ServletWebSecurityAutoConfiguration.class
})
@AutoConfigureMockMvc(addFilters = false)
@Import(ChatRoomListControllerTest.TestAuthenticationPrincipalConfig.class)
class ChatRoomListControllerTest {

    private static final Long AUTHENTICATED_USER_ID = 1L;
    private static final LocalDateTime ACTIVITY_AT = LocalDateTime.of(2026, 9, 26, 14, 30);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatRoomListService chatRoomListService;

    @MockitoBean
    private ChatRoomParticipantDisplayService participantDisplayService;

    @Test
    void 채팅방_목록을_조회하면_확정된_응답_계약을_반환한다() throws Exception {
        ChatRoomSummary summary = new ChatRoomSummary(
            100L,
            55L,
            true,
            new ChatRoomPreview(ChatRoomPreviewType.TEXT, "안녕하세요!"),
            ACTIVITY_AT);
        given(chatRoomListService.listRooms(AUTHENTICATED_USER_ID, null, 20))
            .willReturn(new ChatRoomPage(
                List.of(summary), new ChatRoomPageInfo(null, false)));
        given(participantDisplayService.findNicknames(List.of(55L)))
            .willReturn(Map.of(55L, "상대닉네임"));

        mockMvc.perform(get("/api/v1/chat-rooms"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("chat_room_list_success"))
            .andExpect(jsonPath("$.data.items[0].chatRoomId").value(100))
            .andExpect(jsonPath("$.data.items[0].chatNotification").value(true))
            .andExpect(jsonPath("$.data.items[0].otherParticipant.nickname")
                .value("상대닉네임"))
            .andExpect(jsonPath("$.data.items[0].otherParticipant.profileImageUrl")
                .value(nullValue()))
            .andExpect(jsonPath("$.data.items[0].preview.type").value("TEXT"))
            .andExpect(jsonPath("$.data.items[0].preview.text").value("안녕하세요!"))
            .andExpect(jsonPath("$.data.items[0].activityAt")
                .value("2026-09-26T14:30:00"))
            .andExpect(jsonPath("$.data.items[0].otherUserId").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].status").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].unreadCount").doesNotExist())
            .andExpect(jsonPath("$.data.pageInfo.nextCursor").value(nullValue()))
            .andExpect(jsonPath("$.data.pageInfo.hasNext").value(false));
        verify(chatRoomListService).listRooms(AUTHENTICATED_USER_ID, null, 20);
        verify(participantDisplayService).findNicknames(List.of(55L));
    }

    @Test
    void 조회_결과가_없으면_빈_목록과_다음_페이지_없음을_반환한다() throws Exception {
        given(chatRoomListService.listRooms(AUTHENTICATED_USER_ID, null, 20))
            .willReturn(emptyPage());
        given(participantDisplayService.findNicknames(anyCollection()))
            .willReturn(Map.of());

        mockMvc.perform(get("/api/v1/chat-rooms"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.pageInfo.nextCursor").value(nullValue()))
            .andExpect(jsonPath("$.data.pageInfo.hasNext").value(false));
        verify(participantDisplayService).findNicknames(List.of());
    }

    @Test
    void 응답으로_생성한_커서를_다음_요청에서_원래_정렬키로_복원한다() throws Exception {
        ChatRoomCursor cursor = new ChatRoomCursor(ACTIVITY_AT, 100L);
        given(chatRoomListService.listRooms(AUTHENTICATED_USER_ID, null, 1))
            .willReturn(new ChatRoomPage(
                List.of(), new ChatRoomPageInfo(cursor, true)));
        given(chatRoomListService.listRooms(AUTHENTICATED_USER_ID, cursor, 1))
            .willReturn(emptyPage());
        given(participantDisplayService.findNicknames(anyCollection()))
            .willReturn(Map.of());

        String response = mockMvc.perform(get("/api/v1/chat-rooms").param("size", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.pageInfo.hasNext").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
        String nextCursor = JsonPath.read(response, "$.data.pageInfo.nextCursor");

        mockMvc.perform(get("/api/v1/chat-rooms")
            .param("size", "1")
            .param("cursor", nextCursor))
            .andExpect(status().isOk());

        verify(chatRoomListService).listRooms(AUTHENTICATED_USER_ID, cursor, 1);
    }

    @ParameterizedTest
    @MethodSource("invalidCursors")
    void 잘못된_커서는_오류_코드만_반환한다(String cursor) throws Exception {
        mockMvc.perform(get("/api/v1/chat-rooms").param("cursor", cursor))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_CHAT_ROOM_CURSOR"))
            .andExpect(jsonPath("$.message").doesNotExist())
            .andExpect(jsonPath("$.errors").doesNotExist());
        verifyNoInteractions(chatRoomListService, participantDisplayService);
    }

    @Test
    void 페이지_크기가_허용_범위를_벗어나면_오류_코드만_반환한다() throws Exception {
        given(chatRoomListService.listRooms(AUTHENTICATED_USER_ID, null, 0))
            .willThrow(new ChatBusinessException(ChatErrorCode.INVALID_CHAT_ROOM_PAGE_SIZE));

        mockMvc.perform(get("/api/v1/chat-rooms").param("size", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_CHAT_ROOM_PAGE_SIZE"))
            .andExpect(jsonPath("$.message").doesNotExist())
            .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void 페이지_크기가_숫자가_아니면_공통_요청_오류를_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/chat-rooms").param("size", "abc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").doesNotExist())
            .andExpect(jsonPath("$.errors").doesNotExist());
        verifyNoInteractions(chatRoomListService, participantDisplayService);
    }

    private static Stream<String> invalidCursors() {
        return Stream.of(
            " ",
            "a".repeat(257),
            "not-base64",
            encoded("2|2026-09-26T14:30:00|100"),
            encoded("1|2026-09-26T14:30:00"),
            encoded("1|invalid-time|100"),
            encoded("1|2026-09-26T14:30:00|invalid-id"),
            encoded("1|2026-09-26T14:30:00|0"),
            encoded("1|2026-09-26T14:30:00|-1"));
    }

    private static String encoded(String payload) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private ChatRoomPage emptyPage() {
        return new ChatRoomPage(List.of(), new ChatRoomPageInfo(null, false));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestAuthenticationPrincipalConfig implements WebMvcConfigurer {

        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new HandlerMethodArgumentResolver() {
                @Override
                public boolean supportsParameter(MethodParameter parameter) {
                    return parameter.hasParameterAnnotation(AuthenticationPrincipal.class)
                        && parameter.getParameterType().equals(ServiceAuthenticationPrincipal.class);
                }

                @Override
                public Object resolveArgument(
                    MethodParameter parameter,
                    ModelAndViewContainer mavContainer,
                    NativeWebRequest webRequest,
                    WebDataBinderFactory binderFactory) {
                    return new ServiceAuthenticationPrincipal(AUTHENTICATED_USER_ID);
                }
            });
        }
    }
}
