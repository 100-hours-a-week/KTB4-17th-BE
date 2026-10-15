package com.team.dating_backend.profiletrust.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.team.dating_backend.profiletrust.config.ProfileTrustAiConnectionProperties;
import com.team.dating_backend.profiletrust.enums.ProfileTrustErrorCode;
import com.team.dating_backend.profiletrust.enums.VerificationDecision;
import com.team.dating_backend.profiletrust.exception.ProfileTrustBusinessException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestFaceVerificationAiClientTest {

    private MockRestServiceServer server;
    private RestFaceVerificationAiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        ProfileTrustAiConnectionProperties properties = new ProfileTrustAiConnectionProperties();
        properties.setBaseUrl("http://ai.internal/");
        properties.setApiKey("existing-key");
        client = new RestFaceVerificationAiClient(builder.build(), properties);
    }

    @Test
    void 기존_AI_주소와_인증키로_챌린지를_발급한다() {
        server.expect(requestTo("http://ai.internal/ai/api/v1/profile-trust/verifications/challenges"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-Internal-Api-Key", "existing-key"))
            .andRespond(withSuccess("""
                {"challenge_token":"signed","challenges":["LOOK_STRAIGHT"],"expires_at":"2030-01-01T00:00:00Z"}
                """, MediaType.APPLICATION_JSON));
        assertThat(client.createChallenge().challengeToken()).isEqualTo("signed");
        server.verify();
    }

    @Test
    void 사진_영상_토큰을_AI_계약의_multipart_필드로_전달한다() {
        server.expect(requestTo("http://ai.internal/ai/api/v1/profile-trust/verifications/complete"))
            .andExpect(header("X-Internal-Api-Key", "existing-key"))
            .andExpect(request -> {
                String body = ((MockClientHttpRequest) request).getBodyAsString();
                assertThat(body).contains("name=\"challenge_token\"", "signed",
                    "name=\"primary_photo\"", "PHOTO", "Content-Type: image/png",
                    "name=\"live_video\"", "VIDEO", "Content-Type: video/webm");
            })
            .andRespond(withSuccess("""
                {"decision":"VERIFIED","reason_codes":[],"liveness":{"passed":true},"model_version":"sface"}
                """, MediaType.APPLICATION_JSON));
        assertThat(client.verify("signed", "PHOTO".getBytes(StandardCharsets.UTF_8), "image/png",
            "VIDEO".getBytes(StandardCharsets.UTF_8), "video/webm").decision())
            .isEqualTo(VerificationDecision.VERIFIED);
        server.verify();
    }

    @Test
    void 유효하지_않은_챌린지와_서버_오류를_ErrorCode로_매핑한다() {
        server.expect(requestTo("http://ai.internal/ai/api/v1/profile-trust/verifications/complete"))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("invalid token"));
        assertFailure(ProfileTrustErrorCode.VERIFICATION_CHALLENGE_INVALID);
        server.verify();
    }

    @Test
    void 잘못된_JSON은_AI_응답_오류로_처리한다() {
        server.expect(requestTo("http://ai.internal/ai/api/v1/profile-trust/verifications/complete"))
            .andRespond(withSuccess("{broken", MediaType.APPLICATION_JSON));
        assertFailure(ProfileTrustErrorCode.AI_SERVER_RESPONSE_INVALID);
        server.verify();
    }

    private void assertFailure(ProfileTrustErrorCode code) {
        assertThatThrownBy(() -> client.verify("signed", new byte[]{1}, "image/png", new byte[]{2}, "video/webm"))
            .isInstanceOfSatisfying(ProfileTrustBusinessException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(code);
                assertThat(exception.getMessage()).isNull();
            });
    }
}
