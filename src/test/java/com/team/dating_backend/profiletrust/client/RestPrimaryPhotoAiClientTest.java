package com.team.dating_backend.profiletrust.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.team.dating_backend.profiletrust.config.ProfileTrustAiConnectionProperties;
import com.team.dating_backend.profiletrust.enums.ProfileTrustErrorCode;
import com.team.dating_backend.profiletrust.exception.ProfileTrustBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestPrimaryPhotoAiClientTest {

    private static final byte[] IMAGE = {1, 2, 3};
    private MockRestServiceServer server;
    private ProfileTrustAiConnectionProperties properties;
    private RestPrimaryPhotoAiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        properties = new ProfileTrustAiConnectionProperties();
        properties.setBaseUrl("http://ai.internal/");
        client = new RestPrimaryPhotoAiClient(builder.build(), properties);
    }

    @Test
    void 기존_AI_주소와_인증키로_두_사진_API를_호출한다() {
        properties.setApiKey("internal-test-key");
        server.expect(requestTo(
            "http://ai.internal/ai/api/v1/profile-trust/photos/primary/frontal-check"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-Internal-Api-Key", "internal-test-key"))
            .andRespond(withSuccess(
                "{\"decision\":\"PASS\",\"reason_codes\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(
            "http://ai.internal/ai/api/v1/profile-trust/photos/primary/synthetic-check"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-Internal-Api-Key", "internal-test-key"))
            .andRespond(withSuccess("{\"decision\":\"CLEAR\"}", MediaType.APPLICATION_JSON));

        assertThat(client.checkFrontal(IMAGE, "image/png").decision()).isEqualTo("PASS");
        assertThat(client.checkSynthetic(IMAGE, "image/png").decision()).isEqualTo("CLEAR");
        server.verify();
    }

    @Test
    void 기존_인증키가_비어_있으면_인증_헤더를_생략한다() {
        server.expect(requestTo(
            "http://ai.internal/ai/api/v1/profile-trust/photos/primary/frontal-check"))
            .andExpect(headerDoesNotExist("X-Internal-Api-Key"))
            .andRespond(withSuccess(
                "{\"decision\":\"PASS\",\"reason_codes\":[]}", MediaType.APPLICATION_JSON));

        client.checkFrontal(IMAGE, "image/png");

        server.verify();
    }

    @Test
    void 기존_AI_주소가_없으면_ErrorCode로_처리한다() {
        properties.setBaseUrl("");

        assertThatThrownBy(() -> client.checkFrontal(IMAGE, "image/png"))
            .isInstanceOfSatisfying(ProfileTrustBusinessException.class, exception -> {
                assertThat(exception.getErrorCode())
                    .isEqualTo(ProfileTrustErrorCode.AI_SERVER_NOT_CONFIGURED);
                assertThat(exception.getMessage()).isNull();
            });
    }

    @Test
    void 별도_사진검증_설정_없이_기존_AI_설정으로_빈을_생성한다() {
        new ApplicationContextRunner()
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withUserConfiguration(ConnectionConfiguration.class)
            .withPropertyValues("app.ai-practice.base-url=http://ai.internal",
                "app.ai-practice.api-key=existing-key")
            .withBean(RestPrimaryPhotoAiClient.class)
            .run(context -> {
                assertThat(context).hasSingleBean(RestPrimaryPhotoAiClient.class);
                ProfileTrustAiConnectionProperties connection = context
                    .getBean(ProfileTrustAiConnectionProperties.class);
                assertThat(connection.getBaseUrl()).isEqualTo("http://ai.internal");
                assertThat(connection.getApiKey()).isEqualTo("existing-key");
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ProfileTrustAiConnectionProperties.class)
    static class ConnectionConfiguration {}
}
