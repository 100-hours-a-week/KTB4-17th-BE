package com.team.dating_backend.profiletrust.client;

import com.team.dating_backend.aipractice.config.AiPracticeProperties;
import com.team.dating_backend.profiletrust.dto.ai.PrimaryPhotoAiPayloads.FrontalCheckResponse;
import com.team.dating_backend.profiletrust.dto.ai.PrimaryPhotoAiPayloads.SyntheticCheckResponse;
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
public class RestPrimaryPhotoAiClient implements PrimaryPhotoAiClient {

    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";
    private static final String FRONTAL_CHECK_PATH =
        "/ai/api/v1/profile-trust/photos/primary/frontal-check";
    private static final String SYNTHETIC_CHECK_PATH =
        "/ai/api/v1/profile-trust/photos/primary/synthetic-check";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final RestClient restClient;
    private final AiPracticeProperties properties;

    @Autowired
    public RestPrimaryPhotoAiClient(
        RestClient.Builder restClientBuilder,
        AiPracticeProperties properties) {
        this(createRestClient(restClientBuilder), properties);
    }

    private static RestClient createRestClient(RestClient.Builder restClientBuilder) {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return restClientBuilder.requestFactory(requestFactory).build();
    }

    RestPrimaryPhotoAiClient(RestClient restClient, AiPracticeProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public FrontalCheckResponse checkFrontal(byte[] image, String contentType) {
        return postImage(FRONTAL_CHECK_PATH, image, contentType,
            FrontalCheckResponse.class);
    }

    @Override
    public SyntheticCheckResponse checkSynthetic(byte[] image, String contentType) {
        return postImage(SYNTHETIC_CHECK_PATH, image, contentType,
            SyntheticCheckResponse.class);
    }

    private <T> T postImage(String path, byte[] image, String contentType, Class<T> responseType) {
        try {
            HttpHeaders imageHeaders = new HttpHeaders();
            imageHeaders.setContentType(MediaType.parseMediaType(contentType));
            ByteArrayResource imageResource = new ByteArrayResource(image) {
                @Override
                public String getFilename() {
                    return "primary-photo";
                }
            };
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("image", new HttpEntity<>(imageResource, imageHeaders));

            RestClient.RequestBodySpec request = restClient.post()
                .uri(uri(path))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .accept(MediaType.APPLICATION_JSON);
            if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
                request.header(INTERNAL_API_KEY_HEADER, properties.getApiKey());
            }
            return request.body(body).retrieve().body(responseType);
        } catch (RestClientResponseException exception) {
            throw mapResponseException(exception);
        } catch (RestClientException exception) {
            throw new ProfileTrustBusinessException(
                ProfileTrustErrorCode.AI_SERVER_UNAVAILABLE, exception);
        } catch (IllegalArgumentException exception) {
            throw new ProfileTrustBusinessException(
                ProfileTrustErrorCode.AI_SERVER_RESPONSE_INVALID, exception);
        }
    }

    private URI uri(String path) {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()
            || path == null || path.isBlank()) {
            throw new ProfileTrustBusinessException(
                ProfileTrustErrorCode.AI_SERVER_NOT_CONFIGURED);
        }
        String baseUrl = properties.getBaseUrl().replaceAll("/$", "");
        String relativePath = path.startsWith("/") ? path : "/" + path;
        return URI.create(baseUrl + relativePath);
    }

    private ProfileTrustBusinessException mapResponseException(
        RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        ProfileTrustErrorCode errorCode = status >= 500
            ? ProfileTrustErrorCode.AI_SERVER_UNAVAILABLE
            : status == 401 || status == 403 || status == 422
                ? ProfileTrustErrorCode.AI_REQUEST_REJECTED
                : ProfileTrustErrorCode.AI_SERVER_RESPONSE_INVALID;
        return new ProfileTrustBusinessException(errorCode, exception);
    }
}
