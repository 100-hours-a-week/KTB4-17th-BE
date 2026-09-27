package com.team.dating_backend.matching.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.matching.dto.response.LikeCreateResponse;
import com.team.dating_backend.matching.dto.response.ReceivedLikeItemResponse;
import com.team.dating_backend.matching.dto.response.ReceivedLikePageInfo;
import com.team.dating_backend.matching.dto.response.ReceivedLikeSenderResponse;
import com.team.dating_backend.matching.dto.response.ReceivedLikesGetResponse;
import com.team.dating_backend.matching.dto.response.SentLikeItemResponse;
import com.team.dating_backend.matching.dto.response.SentLikePageInfo;
import com.team.dating_backend.matching.dto.response.SentLikeReceiverResponse;
import com.team.dating_backend.matching.dto.response.SentLikesGetResponse;
import com.team.dating_backend.matching.enums.LikeErrorCode;
import com.team.dating_backend.matching.enums.LikeStatus;
import com.team.dating_backend.matching.exception.LikeBusinessException;
import com.team.dating_backend.matching.service.LikeSendService;
import com.team.dating_backend.matching.service.ReceivedLikeGetService;
import com.team.dating_backend.matching.service.SentLikeGetService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import java.time.LocalDateTime;
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
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@WebMvcTest(
    controllers = LikeController.class,
    excludeAutoConfiguration = {
        SecurityAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class
    }
)
@AutoConfigureMockMvc(addFilters = false)
@Import(LikeControllerTest.TestAuthenticationPrincipalConfig.class)
class LikeControllerTest {

    private static final Long AUTHENTICATED_MEMBER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LikeSendService likeSendService;

    @MockitoBean
    private SentLikeGetService sentLikeGetService;

    @MockitoBean
    private ReceivedLikeGetService receivedLikeGetService;

    @Test
    void 로그인한_사용자가_좋아요를_전송하면_201과_PENDING을_반환한다() throws Exception {
        given(likeSendService.sendLike(1L, 2L))
            .willReturn(new LikeCreateResponse(10L, LikeStatus.PENDING));

        mockMvc.perform(authenticatedPost("{\"receiverId\":2}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.message").value("like_create_success"))
            .andExpect(jsonPath("$.data.likeId").value(10))
            .andExpect(jsonPath("$.data.status").value("PENDING"));
        verify(likeSendService).sendLike(1L, 2L);
    }

    @Test
    void 상호_좋아요가_성립하면_201과_MATCHED를_반환한다() throws Exception {
        given(likeSendService.sendLike(1L, 2L))
            .willReturn(new LikeCreateResponse(11L, LikeStatus.MATCHED));

        mockMvc.perform(authenticatedPost("{\"receiverId\":2}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.message").value("like_create_success"))
            .andExpect(jsonPath("$.data.likeId").value(11))
            .andExpect(jsonPath("$.data.status").value("MATCHED"));
    }

    @Test
    void 수신자_ID가_유효하지_않으면_400을_반환한다() throws Exception {
        mockMvc.perform(authenticatedPost("{\"receiverId\":0}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(likeSendService);
    }

    @Test
    void 중복_PENDING은_409를_반환한다() throws Exception {
        given(likeSendService.sendLike(1L, 2L))
            .willThrow(new LikeBusinessException(LikeErrorCode.DUPLICATE_PENDING_LIKE));
        mockMvc.perform(authenticatedPost("{\"receiverId\":2}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("DUPLICATE_PENDING_LIKE"));
    }

    @Test
    void 사용할_수_없는_수신자는_404를_반환한다() throws Exception {
        given(likeSendService.sendLike(1L, 2L))
            .willThrow(new LikeBusinessException(LikeErrorCode.MEMBER_NOT_FOUND));
        mockMvc.perform(authenticatedPost("{\"receiverId\":2}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("MEMBER_NOT_FOUND"));
    }

    @Test
    void 자기_자신에게_전송하면_422를_반환한다() throws Exception {
        given(likeSendService.sendLike(1L, 1L))
            .willThrow(new LikeBusinessException(LikeErrorCode.SELF_LIKE_NOT_ALLOWED));
        mockMvc.perform(authenticatedPost("{\"receiverId\":1}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("SELF_LIKE_NOT_ALLOWED"));
    }

    @Test
    void 보낸_좋아요를_조회하면_200과_상대_정보를_반환한다() throws Exception {
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 27, 12, 30);
        SentLikeReceiverResponse receiver = new SentLikeReceiverResponse(
            20L,
            "하리",
            "https://example.com/profile",
            27,
            "개발자",
            "서울특별시 강남구");
        given(sentLikeGetService.getSentLikes(1L, 120L))
            .willReturn(new SentLikesGetResponse(
                List.of(new SentLikeItemResponse(
                    101L, receiver, LikeStatus.PENDING, createdAt)),
                new SentLikePageInfo(101L, true)));

        mockMvc.perform(get("/api/v1/likes/sent").param("cursor", "120"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("sent_like_get_success"))
            .andExpect(jsonPath("$.data.items[0].likeId").value(101))
            .andExpect(jsonPath("$.data.items[0].receiver.memberId").value(20))
            .andExpect(jsonPath("$.data.items[0].receiver.nickname").value("하리"))
            .andExpect(jsonPath("$.data.items[0].receiver.profileImageUrl")
                .value("https://example.com/profile"))
            .andExpect(jsonPath("$.data.items[0].receiver.age").value(27))
            .andExpect(jsonPath("$.data.items[0].receiver.job").value("개발자"))
            .andExpect(jsonPath("$.data.items[0].receiver.region")
                .value("서울특별시 강남구"))
            .andExpect(jsonPath("$.data.items[0].status").value("PENDING"))
            .andExpect(jsonPath("$.data.items[0].createdAt")
                .value("2026-09-27T12:30:00"))
            .andExpect(jsonPath("$.data.pageInfo.nextCursor").value(101))
            .andExpect(jsonPath("$.data.pageInfo.hasNext").value(true));
        verify(sentLikeGetService).getSentLikes(1L, 120L);
    }

    @Test
    void 보낸_좋아요가_없으면_200과_빈_목록을_반환한다() throws Exception {
        given(sentLikeGetService.getSentLikes(1L, null))
            .willReturn(new SentLikesGetResponse(
                List.of(), new SentLikePageInfo(null, false)));

        mockMvc.perform(get("/api/v1/likes/sent"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.pageInfo.nextCursor").value(nullValue()))
            .andExpect(jsonPath("$.data.pageInfo.hasNext").value(false));
    }

    @Test
    void 보낸_좋아요_조회_커서의_타입이_잘못되면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/likes/sent").param("cursor", "invalid"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(sentLikeGetService);
    }

    @Test
    void 받은_좋아요를_조회하면_200과_상대_정보를_반환한다() throws Exception {
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 27, 12, 30);
        ReceivedLikeSenderResponse sender = new ReceivedLikeSenderResponse(
            20L,
            "하리",
            "https://example.com/profile",
            27,
            "개발자",
            "서울특별시 강남구");
        given(receivedLikeGetService.getReceivedLikes(1L, 120L))
            .willReturn(new ReceivedLikesGetResponse(
                List.of(new ReceivedLikeItemResponse(
                    101L, sender, LikeStatus.PENDING, createdAt)),
                new ReceivedLikePageInfo(101L, true)));

        mockMvc.perform(get("/api/v1/likes/received").param("cursor", "120"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("received_like_get_success"))
            .andExpect(jsonPath("$.data.items[0].likeId").value(101))
            .andExpect(jsonPath("$.data.items[0].sender.memberId").value(20))
            .andExpect(jsonPath("$.data.items[0].sender.nickname").value("하리"))
            .andExpect(jsonPath("$.data.items[0].sender.profileImageUrl")
                .value("https://example.com/profile"))
            .andExpect(jsonPath("$.data.items[0].sender.age").value(27))
            .andExpect(jsonPath("$.data.items[0].sender.job").value("개발자"))
            .andExpect(jsonPath("$.data.items[0].sender.region")
                .value("서울특별시 강남구"))
            .andExpect(jsonPath("$.data.items[0].status").value("PENDING"))
            .andExpect(jsonPath("$.data.items[0].createdAt")
                .value("2026-09-27T12:30:00"))
            .andExpect(jsonPath("$.data.pageInfo.nextCursor").value(101))
            .andExpect(jsonPath("$.data.pageInfo.hasNext").value(true));
        verify(receivedLikeGetService).getReceivedLikes(1L, 120L);
    }

    @Test
    void 받은_좋아요가_없으면_200과_빈_목록을_반환한다() throws Exception {
        given(receivedLikeGetService.getReceivedLikes(1L, null))
            .willReturn(new ReceivedLikesGetResponse(
                List.of(), new ReceivedLikePageInfo(null, false)));

        mockMvc.perform(get("/api/v1/likes/received"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.pageInfo.nextCursor").value(nullValue()))
            .andExpect(jsonPath("$.data.pageInfo.hasNext").value(false));
    }

    @Test
    void 받은_좋아요_조회_커서의_타입이_잘못되면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/likes/received").param("cursor", "invalid"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(receivedLikeGetService);
    }

    private MockHttpServletRequestBuilder authenticatedPost(String body) {
        return post("/api/v1/likes")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
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
                    return new ServiceAuthenticationPrincipal(AUTHENTICATED_MEMBER_ID);
                }
            });
        }
    }
}
