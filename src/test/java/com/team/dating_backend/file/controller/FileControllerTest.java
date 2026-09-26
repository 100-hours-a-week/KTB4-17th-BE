package com.team.dating_backend.file.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.file.dto.CreateFileUploadIntentCommand;
import com.team.dating_backend.file.dto.FileAccessUrlResult;
import com.team.dating_backend.file.dto.FileMetadataResult;
import com.team.dating_backend.file.dto.FileUploadIntentResult;
import com.team.dating_backend.file.service.FileService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

@WebMvcTest(controllers = FileController.class, excludeAutoConfiguration = {
    SecurityAutoConfiguration.class,
    ServletWebSecurityAutoConfiguration.class
})
@AutoConfigureMockMvc(addFilters = false)
@Import(FileControllerTest.TestAuthenticationPrincipalConfig.class)
class FileControllerTest {

    private static final Long USER_ID = 41L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FileService fileService;

    @Test
    void 업로드_intent를_생성한다() throws Exception {
        given(fileService.createUploadIntent(
            USER_ID,
            new CreateFileUploadIntentCommand("photo.png", "image/png")))
            .willReturn(new FileUploadIntentResult(
                7L,
                "https://s3.example.com/upload",
                "PUT",
                Map.of("Content-Type", "image/png"),
                Instant.parse("2026-09-26T01:10:00Z"),
                Instant.parse("2026-09-26T01:15:00Z"),
                10_485_760));

        mockMvc.perform(authenticatedPost("/api/v1/files/upload-intents")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"originalName\":\"photo.png\",\"contentType\":\"image/png\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.message").value("file_upload_intent_create_success"))
            .andExpect(jsonPath("$.data.uploadIntentId").value(7))
            .andExpect(jsonPath("$.data.method").value("PUT"))
            .andExpect(jsonPath("$.data.headers.Content-Type").value("image/png"))
            .andExpect(jsonPath("$.data.maxFileSizeBytes").value(10_485_760));

        verify(fileService).createUploadIntent(
            USER_ID,
            new CreateFileUploadIntentCommand("photo.png", "image/png"));
    }

    @Test
    void S3_업로드_완료를_검증하고_메타데이터를_반환한다() throws Exception {
        given(fileService.completeUpload(USER_ID, 7L))
            .willReturn(new FileMetadataResult(
                19L,
                "photo.png",
                "image/png",
                512L,
                LocalDateTime.parse("2026-09-26T10:00:00")));

        mockMvc.perform(authenticatedPost("/api/v1/files/upload-intents/7/complete"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("file_upload_complete_success"))
            .andExpect(jsonPath("$.data.fileId").value(19))
            .andExpect(jsonPath("$.data.fileSize").value(512));

        verify(fileService).completeUpload(USER_ID, 7L);
    }

    @Test
    void 다운로드_URL_발급시_disposition을_전달한다() throws Exception {
        given(fileService.createAccessUrl(USER_ID, 19L, "inline"))
            .willReturn(new FileAccessUrlResult(
                19L,
                "https://s3.example.com/download",
                "inline",
                Instant.parse("2026-09-26T01:05:00Z")));

        mockMvc.perform(get("/api/v1/files/19/access-url")
            .requestAttr(
                "testAuthenticationPrincipal",
                new ServiceAuthenticationPrincipal(USER_ID))
            .param("disposition", "inline"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.fileId").value(19))
            .andExpect(jsonPath("$.data.disposition").value("inline"))
            .andExpect(jsonPath("$.data.accessUrl").value("https://s3.example.com/download"));

        verify(fileService).createAccessUrl(USER_ID, 19L, "inline");
    }

    @Test
    void 파일_메타데이터를_조회한다() throws Exception {
        given(fileService.getMetadata(USER_ID, 19L))
            .willReturn(new FileMetadataResult(
                19L,
                "photo.png",
                "image/png",
                512L,
                LocalDateTime.parse("2026-09-26T10:00:00")));

        mockMvc.perform(authenticatedGet("/api/v1/files/19/metadata"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("file_metadata_get_success"))
            .andExpect(jsonPath("$.data.fileId").value(19))
            .andExpect(jsonPath("$.data.originalName").value("photo.png"))
            .andExpect(jsonPath("$.data.fileSize").value(512));

        verify(fileService).getMetadata(USER_ID, 19L);
    }

    @Test
    void 파일을_논리삭제한다() throws Exception {
        mockMvc.perform(delete("/api/v1/files/19")
            .requestAttr(
                "testAuthenticationPrincipal",
                new ServiceAuthenticationPrincipal(USER_ID)))
            .andExpect(status().isNoContent());

        verify(fileService).softDelete(USER_ID, 19L);
    }

    @Test
    void 파일명과_MIME이_비어있으면_400을_반환한다() throws Exception {
        mockMvc.perform(authenticatedPost("/api/v1/files/upload-intents")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"originalName\":\"\",\"contentType\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    private MockHttpServletRequestBuilder authenticatedPost(String path) {
        return post(path).requestAttr(
            "testAuthenticationPrincipal",
            new ServiceAuthenticationPrincipal(USER_ID));
    }

    private MockHttpServletRequestBuilder authenticatedGet(String path) {
        return get(path).requestAttr(
            "testAuthenticationPrincipal",
            new ServiceAuthenticationPrincipal(USER_ID));
    }

    @TestConfiguration
    static class TestAuthenticationPrincipalConfig implements WebMvcConfigurer {

        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new HandlerMethodArgumentResolver() {
                @Override
                public boolean supportsParameter(MethodParameter parameter) {
                    return parameter.hasParameterAnnotation(AuthenticationPrincipal.class)
                        && parameter.getParameterType() == ServiceAuthenticationPrincipal.class;
                }

                @Override
                public Object resolveArgument(
                    MethodParameter parameter,
                    ModelAndViewContainer mavContainer,
                    NativeWebRequest webRequest,
                    WebDataBinderFactory binderFactory) {
                    return webRequest.getAttribute(
                        "testAuthenticationPrincipal",
                        NativeWebRequest.SCOPE_REQUEST);
                }
            });
        }
    }
}
