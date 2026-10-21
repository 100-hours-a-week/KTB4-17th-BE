package com.team.dating_backend.profiletrust.client;

import com.team.dating_backend.profiletrust.config.ProfileTrustAiConnectionProperties;
import com.team.dating_backend.profiletrust.dto.ai.FaceVerificationAiPayloads.ChallengeResponse;
import com.team.dating_backend.profiletrust.dto.ai.FaceVerificationAiPayloads.VerificationResponse;
import com.team.dating_backend.profiletrust.enums.ProfileTrustErrorCode;
import com.team.dating_backend.profiletrust.exception.ProfileTrustBusinessException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class RestFaceVerificationAiClient implements FaceVerificationAiClient {

    private static final String CHALLENGE_PATH = "/ai/api/v1/profile-trust/verifications/challenges";
    private static final String COMPLETE_PATH = "/ai/api/v1/profile-trust/verifications/complete";
    private final RestClient restClient;
    private final ProfileTrustAiConnectionProperties properties;

    @Autowired
    public RestFaceVerificationAiClient(RestClient.Builder builder, ProfileTrustAiConnectionProperties properties) {
        this(createRestClient(builder), properties);
    }

    RestFaceVerificationAiClient(RestClient restClient, ProfileTrustAiConnectionProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    private static RestClient createRestClient(RestClient.Builder builder) {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .version(HttpClient.Version.HTTP_1_1).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(30));
        return builder.clone().requestFactory(factory).build();
    }

    @Override
    public ChallengeResponse createChallenge() {
        return post(CHALLENGE_PATH, null, ChallengeResponse.class);
    }

    @Override
    public VerificationResponse verify(
        String challengeToken, byte[] primaryPhoto, String photoContentType,
        byte[] liveVideo, String videoContentType) {
        return verify(challengeToken, primaryPhoto, photoContentType, liveVideo, videoContentType, 1);
    }

    @Override
    public VerificationResponse verify(
        String challengeToken, byte[] primaryPhoto, String photoContentType,
        byte[] liveVideo, String videoContentType, int attemptNo) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("challenge_token", challengeToken);
        body.add("primary_photo", part(primaryPhoto, photoContentType, "primary-photo"));
        body.add("live_video", part(liveVideo, videoContentType, "live-video"));
        body.add("attempt_no", attemptNo);
        return post(COMPLETE_PATH, body, VerificationResponse.class);
    }

    private HttpEntity<ByteArrayResource> part(byte[] bytes, String contentType, String filename) {
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        return new HttpEntity<>(resource, headers);
    }

    private <T> T post(String path, MultiValueMap<String, Object> body, Class<T> responseType) {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new ProfileTrustBusinessException(ProfileTrustErrorCode.AI_SERVER_NOT_CONFIGURED);
        }
        try {
            URI uri = URI.create(properties.getBaseUrl().replaceAll("/$", "") + path);
            RestClient.RequestBodySpec request = restClient.post().uri(uri).accept(MediaType.APPLICATION_JSON);
            if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
                request.header("X-Internal-Api-Key", properties.getApiKey());
            }
            if (body != null) {
                request.contentType(MediaType.MULTIPART_FORM_DATA).body(body);
            }
            return request.retrieve().body(responseType);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            ProfileTrustErrorCode code;
            if (status == 400) {
                code = ProfileTrustErrorCode.VERIFICATION_CHALLENGE_INVALID;
            } else if (status == 429 || status >= 500) {
                code = ProfileTrustErrorCode.AI_SERVER_UNAVAILABLE;
            } else {
                code = ProfileTrustErrorCode.AI_REQUEST_REJECTED;
            }
            throw new ProfileTrustBusinessException(code, exception);
        } catch (RestClientException exception) {
            boolean conversionFailure = false;
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                String name = cause.getClass().getName();
                conversionFailure |= name.startsWith("tools.jackson.") || name.contains("MessageConversion");
            }
            throw new ProfileTrustBusinessException(conversionFailure
                ? ProfileTrustErrorCode.AI_SERVER_RESPONSE_INVALID
                : ProfileTrustErrorCode.AI_SERVER_UNAVAILABLE,
                exception);
        } catch (IllegalArgumentException exception) {
            throw new ProfileTrustBusinessException(ProfileTrustErrorCode.AI_SERVER_NOT_CONFIGURED, exception);
        }
    }
}
