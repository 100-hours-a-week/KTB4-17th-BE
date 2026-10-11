package com.team.dating_backend.persona.kakao.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

public final class KakaoAiPayloads {

    private KakaoAiPayloads() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JobCreated(@JsonProperty("job_id") String jobId, String status) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JobResponse(
        @JsonProperty("job_id") String jobId,
        String kind,
        String status,
        @JsonProperty("persona_id") String personaId,
        @JsonProperty("analyzed_count") Integer analyzedCount,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("finished_at") String finishedAt) {

        public LocalDateTime createdAtUtc() {
            return utc(createdAt);
        }

        public LocalDateTime finishedAtUtc() {
            return finishedAt == null ? null : utc(finishedAt);
        }
    }

    private static LocalDateTime utc(String value) {
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException exception) {
            // The AI repository can return naive UTC datetimes on databases without
            // timezone support.
            return LocalDateTime.parse(value);
        }
    }
}
