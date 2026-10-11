package com.team.dating_backend.persona.kakao.client;

import com.team.dating_backend.persona.kakao.config.KakaoConversationProperties;
import com.team.dating_backend.persona.kakao.dto.ai.KakaoAiPayloads.JobCreated;
import com.team.dating_backend.persona.kakao.dto.ai.KakaoAiPayloads.JobResponse;
import com.team.dating_backend.persona.kakao.enums.KakaoConversationErrorCode;
import com.team.dating_backend.persona.kakao.exception.KakaoConversationBusinessException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.DateTimeException;
import java.util.Set;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class RestKakaoConversationAiClient implements KakaoConversationAiClient {

    private static final Set<String> JOB_STATUSES = Set.of("pending", "running", "succeeded", "failed");

    private final RestClient restClient;
    private final KakaoConversationProperties properties;

    public RestKakaoConversationAiClient(RestClient.Builder builder, KakaoConversationProperties properties) {
        this.properties = properties;
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        this.restClient = builder.clone().requestFactory(factory).build();
    }

    @Override
    public void requireConfigured() {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw failure(KakaoConversationErrorCode.AI_SERVER_NOT_CONFIGURED);
        }
        try {
            URI base = URI.create(properties.getBaseUrl());
            if (base.getHost() == null || !Set.of("http", "https").contains(base.getScheme())) {
                throw failure(KakaoConversationErrorCode.AI_SERVER_NOT_CONFIGURED);
            }
        } catch (IllegalArgumentException exception) {
            throw failure(KakaoConversationErrorCode.AI_SERVER_NOT_CONFIGURED);
        }
    }

    @Override
    public JobCreated submit(Long userId, String speakerName, String filename, byte[] bytes) {
        requireConfigured();
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("user_id", userId.toString());
        HttpHeaders speakerHeaders = new HttpHeaders();
        speakerHeaders.setContentType(new MediaType("text", "plain", StandardCharsets.UTF_8));
        parts.add("speaker_name", new HttpEntity<>(speakerName, speakerHeaders));
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        parts.add("file", new HttpEntity<>(resource, headers));
        try {
            ResponseEntity<JobCreated> reply = restClient.post()
                .uri(uri(properties.getConversationPath()))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .accept(MediaType.APPLICATION_JSON)
                .body(parts).retrieve().toEntity(JobCreated.class);
            JobCreated body = reply.getBody();
            if (reply.getStatusCode() != HttpStatus.ACCEPTED || body == null
                || !validIdentifier(body.jobId()) || !"pending".equals(body.status())) {
                // A malformed acceptance reply may still have created a job.
                throw failure(KakaoConversationErrorCode.AI_SUBMISSION_OUTCOME_UNKNOWN);
            }
            return body;
        } catch (RestClientResponseException exception) {
            throw submissionFailure(exception.getStatusCode().value());
        } catch (RestClientException exception) {
            // An upload may already have been accepted. Never retry POST automatically.
            throw failure(KakaoConversationErrorCode.AI_SUBMISSION_OUTCOME_UNKNOWN);
        }
    }

    @Override
    public JobResponse getJob(String jobId) {
        requireConfigured();
        URI jobUri = UriComponentsBuilder.fromUriString(url(properties.getJobPath()))
            .buildAndExpand(jobId).encode().toUri();
        try {
            JobResponse body = restClient.get().uri(jobUri).accept(MediaType.APPLICATION_JSON)
                .retrieve().body(JobResponse.class);
            if (body == null || !jobId.equals(body.jobId()) || !"conversation".equals(body.kind())
                || body.status() == null || !JOB_STATUSES.contains(body.status())
                || body.analyzedCount() == null || body.analyzedCount() < 0
                || body.createdAt() == null
                || body.personaId() != null && !validIdentifier(body.personaId())
                || "succeeded".equals(body.status()) && !validIdentifier(body.personaId())) {
                throw failure(KakaoConversationErrorCode.AI_SERVER_RESPONSE_INVALID);
            }
            try {
                body.createdAtUtc();
                body.finishedAtUtc();
            } catch (DateTimeException exception) {
                throw failure(KakaoConversationErrorCode.AI_SERVER_RESPONSE_INVALID);
            }
            return body;
        } catch (RestClientResponseException exception) {
            throw failure(exception.getStatusCode().value() == 404
                ? KakaoConversationErrorCode.AI_JOB_NOT_FOUND
                : KakaoConversationErrorCode.AI_SERVER_UNAVAILABLE);
        } catch (RestClientException exception) {
            throw failure(KakaoConversationErrorCode.AI_SERVER_UNAVAILABLE);
        }
    }

    private URI uri(String path) {
        return URI.create(url(path));
    }

    private String url(String path) {
        return properties.getBaseUrl().replaceAll("/$", "")
            + (path.startsWith("/") ? path : "/" + path);
    }

    private KakaoConversationBusinessException submissionFailure(int status) {
        KakaoConversationErrorCode code = switch (status) {
            case 404 -> KakaoConversationErrorCode.PERSONA_CONFIRMATION_REQUIRED;
            case 409 -> KakaoConversationErrorCode.AI_EXTRACTION_IN_PROGRESS;
            case 413 -> KakaoConversationErrorCode.KAKAO_FILE_TOO_LARGE;
            case 400, 422 -> KakaoConversationErrorCode.AI_EXTRACTION_REQUEST_REJECTED;
            default -> status >= 500
                ? KakaoConversationErrorCode.AI_SUBMISSION_OUTCOME_UNKNOWN
                : KakaoConversationErrorCode.AI_SERVER_RESPONSE_INVALID;
        };
        return failure(code);
    }

    private boolean validIdentifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,128}");
    }

    private KakaoConversationBusinessException failure(KakaoConversationErrorCode code) {
        // Do not attach upstream exceptions: their messages may contain uploaded text
        // or names.
        return new KakaoConversationBusinessException(code);
    }
}
