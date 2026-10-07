package com.team.dating_backend.chat.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.chat.dto.response.ChatMessageListResponse;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import com.team.dating_backend.chat.service.ChatMessageListService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import java.util.List;
import org.junit.jupiter.api.Test;
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

@WebMvcTest(
    controllers = ChatMessageListController.class,
    excludeAutoConfiguration = {
        SecurityAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class
    }
)
@AutoConfigureMockMvc(addFilters = false)
@Import(ChatMessageListControllerTest.TestAuthenticationPrincipalConfig.class)
class ChatMessageListControllerTest {

    private static final Long AUTHENTICATED_USER_ID = 1L;
    private static final Long CHAT_ROOM_ID = 7L;
    private static final Long OTHER_USER_ID = 2L;
    private static final String PROFILE_IMAGE_URL = "https://example.com/profile.jpg";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatMessageListService chatMessageListService;

    @Test
    void 상대방_프로필_이미지_URL을_메시지_내역_응답에_반환한다() throws Exception {
        given(chatMessageListService.listMessages(
            CHAT_ROOM_ID, AUTHENTICATED_USER_ID, null, 20))
            .willReturn(new ChatMessageListResponse(
                new ChatMessageListResponse.ChatRoomInfo(
                    CHAT_ROOM_ID,
                    ChatRoomStatus.ACTIVE,
                    true,
                    new ChatMessageListResponse.OtherParticipant(
                        OTHER_USER_ID, "상대방", PROFILE_IMAGE_URL)),
                List.of(),
                new ChatMessageListResponse.PageInfo(null, false)));

        mockMvc.perform(get("/api/v1/chat-rooms/{chatRoomId}/messages", CHAT_ROOM_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.chatRoom.chatRoomId").value(CHAT_ROOM_ID))
            .andExpect(jsonPath("$.data.chatRoom.status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.chatRoom.otherParticipant.memberId")
                .value(OTHER_USER_ID))
            .andExpect(jsonPath("$.data.chatRoom.otherParticipant.nickname")
                .value("상대방"))
            .andExpect(jsonPath("$.data.chatRoom.otherParticipant.profileImageUrl")
                .value(PROFILE_IMAGE_URL));

        verify(chatMessageListService).listMessages(
            CHAT_ROOM_ID, AUTHENTICATED_USER_ID, null, 20);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestAuthenticationPrincipalConfig implements WebMvcConfigurer {

        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new HandlerMethodArgumentResolver() {
                @Override
                public boolean supportsParameter(MethodParameter parameter) {
                    return parameter.hasParameterAnnotation(AuthenticationPrincipal.class)
                        && parameter.getParameterType()
                            .equals(ServiceAuthenticationPrincipal.class);
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
