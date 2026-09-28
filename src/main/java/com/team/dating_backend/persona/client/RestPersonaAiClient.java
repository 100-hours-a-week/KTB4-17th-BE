package com.team.dating_backend.persona.client;

import com.team.dating_backend.persona.config.PersonaAiProperties;
import com.team.dating_backend.persona.dto.ai.PersonaAiPayloads.AnswerRequest;
import com.team.dating_backend.persona.dto.ai.PersonaAiPayloads.ConfirmRequest;
import com.team.dating_backend.persona.dto.ai.PersonaAiPayloads.ConfirmResponse;
import com.team.dating_backend.persona.dto.ai.PersonaAiPayloads.PersonaResponse;
import com.team.dating_backend.persona.dto.ai.PersonaAiPayloads.StartRequest;
import com.team.dating_backend.persona.dto.ai.PersonaAiPayloads.TurnResponse;
import com.team.dating_backend.persona.enums.PersonaErrorCode;
import com.team.dating_backend.persona.exception.PersonaBusinessException;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class RestPersonaAiClient implements PersonaAiClient {

    private final RestClient restClient;
    private final PersonaAiProperties properties;
    private final ObjectMapper objectMapper;

    public RestPersonaAiClient(
        RestClient.Builder restClientBuilder,
        PersonaAiProperties properties,
        ObjectMapper objectMapper) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public TurnResponse start(StartRequest request) {
        return post(properties.getStartPath(), request, TurnResponse.class);
    }

    @Override
    public TurnResponse answer(String sessionId, AnswerRequest request) {
        return post(path(properties.getAnswerPath(), sessionId), request, TurnResponse.class);
    }

    @Override
    public TurnResponse skip(String sessionId) {
        return post(path(properties.getSkipPath(), sessionId), null, TurnResponse.class);
    }

    @Override
    public TurnResponse finish(String sessionId) {
        return post(path(properties.getFinishPath(), sessionId), null, TurnResponse.class);
    }

    @Override
    public PersonaResponse build(String sessionId) {
        return post(path(properties.getBuildPath(), sessionId), null, PersonaResponse.class);
    }

    @Override
    public ConfirmResponse confirm(String personaId, ConfirmRequest request) {
        return post(path(properties.getConfirmPath(), personaId), request, ConfirmResponse.class);
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        try {
            RestClient.RequestBodySpec request = restClient.post()
                .uri(uri(path))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);
            if (body != null) {
                request.body(body);
            }
            return request.retrieve().body(responseType);
        } catch (RestClientResponseException exception) {
            throw mapResponseException(exception);
        } catch (RestClientException exception) {
            throw new PersonaBusinessException(
                PersonaErrorCode.AI_SERVER_UNAVAILABLE, exception);
        }
    }

    private URI uri(String path) {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new PersonaBusinessException(PersonaErrorCode.AI_SERVER_NOT_CONFIGURED);
        }
        String baseUrl = properties.getBaseUrl().replaceAll("/$", "");
        String relativePath = path.startsWith("/") ? path : "/" + path;
        return URI.create(baseUrl + relativePath);
    }

    private String path(String template, String identifier) {
        String pathTemplate = template.replace("%s", "{identifier}");
        return UriComponentsBuilder.fromPath(pathTemplate)
            .buildAndExpand(identifier)
            .encode()
            .toUriString();
    }

    private PersonaBusinessException mapResponseException(
        RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        String body = exception.getResponseBodyAsString();
        String code = detailCode(body);
        String detail = detailText(body).toLowerCase(Locale.ROOT);

        PersonaErrorCode errorCode;
        if (status == 404) {
            errorCode = detail.contains("persona draft")
                ? PersonaErrorCode.PERSONA_DRAFT_NOT_FOUND
                : PersonaErrorCode.SESSION_NOT_FOUND;
        } else if (status == 409) {
            errorCode = conflictCode(code, detail);
        } else if (status == 422) {
            errorCode = PersonaErrorCode.AI_REQUEST_REJECTED;
        } else if (status >= 500) {
            errorCode = PersonaErrorCode.AI_SERVER_UNAVAILABLE;
        } else {
            errorCode = PersonaErrorCode.AI_SERVER_RESPONSE_INVALID;
        }
        return new PersonaBusinessException(errorCode, exception);
    }

    private PersonaErrorCode conflictCode(String code, String detail) {
        if ("request_in_progress".equals(code)) {
            return PersonaErrorCode.REQUEST_IN_PROGRESS;
        }
        if ("turn_mismatch".equals(code)) {
            return PersonaErrorCode.TURN_MISMATCH;
        }
        if (detail.contains("no pending question")) {
            return PersonaErrorCode.NO_PENDING_QUESTION;
        }
        if (detail.contains("need more answers")) {
            return PersonaErrorCode.ACTION_NOT_ALLOWED;
        }
        if (detail.contains("onboarding is not finished")) {
            return PersonaErrorCode.ONBOARDING_NOT_FINISHED;
        }
        if (detail.contains("already confirmed with different")
            || detail.contains("do not match")) {
            return PersonaErrorCode.PERSONA_CONFIRMATION_CONFLICT;
        }
        if (detail.contains("already confirmed")) {
            return PersonaErrorCode.PERSONA_ALREADY_CONFIRMED;
        }
        return PersonaErrorCode.ACTION_NOT_ALLOWED;
    }

    private String detailCode(String body) {
        JsonNode detail = detailNode(body);
        return detail != null && detail.isObject() && detail.hasNonNull("code")
            ? detail.get("code").asText()
            : "";
    }

    private String detailText(String body) {
        JsonNode detail = detailNode(body);
        if (detail == null) {
            return body == null ? "" : body;
        }
        if (detail.isTextual()) {
            return detail.asText();
        }
        if (detail.isObject() && detail.hasNonNull("message")) {
            return detail.get("message").asText();
        }
        return detail.toString();
    }

    private JsonNode detailNode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body).get("detail");
        } catch (Exception ignored) {
            return null;
        }
    }
}
