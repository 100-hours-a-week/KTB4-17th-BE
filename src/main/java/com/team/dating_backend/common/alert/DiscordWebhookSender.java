package com.team.dating_backend.common.alert;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

@RequiredArgsConstructor
public class DiscordWebhookSender {

    private static final int MAX_FIELD_LENGTH = 128;
    private static final int MAX_CONTENT_LENGTH = 2_000;
    private static final String CONTENT_FOOTER = "```\n";

    private final RestClient restClient;

    public void send(String webhookUrl, String title, Map<String, ?> fields) {
        if (!isValidWebhookUrl(webhookUrl) || title == null || title.isBlank()
            || fields == null || fields.isEmpty()) {
            throw new DiscordWebhookException();
        }
        var response = restClient.post()
            .uri(URI.create(webhookUrl + "?wait=true"))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(Map.of("content", content(title, fields),
                "allowed_mentions", Map.of("parse", List.of("everyone"))))
            .retrieve()
            .toEntity(JsonNode.class);

        JsonNode body = response.getBody();
        if (response.getStatusCode() != HttpStatus.OK || body == null
            || !body.path("id").isString() || !body.path("id").stringValue().matches("[0-9]+")) {
            throw new DiscordWebhookException();
        }
    }

    public static boolean isValidWebhookUrl(String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(webhookUrl);
            return "https".equalsIgnoreCase(uri.getScheme())
                && "discord.com".equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                && uri.getRawPath().matches("/api(?:/v[0-9]+)?/webhooks/[0-9]+/[A-Za-z0-9._-]+");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String content(String title, Map<String, ?> values) {
        Map<String, String> fields = new LinkedHashMap<>();
        values.forEach((name, value) -> {
            if (name == null || !name.matches("[A-Za-z][A-Za-z0-9]{0,63}")) {
                throw new DiscordWebhookException();
            }
            fields.put(name, field(value));
        });
        String header = "@everyone " + field(title) + "\n```text\n";
        int available = MAX_CONTENT_LENGTH - header.length() - CONTENT_FOOTER.length()
            - fields.keySet().stream().mapToInt(name -> name.length() + 3).sum();
        if (available < fields.size()) {
            throw new DiscordWebhookException();
        }
        int lower = 1;
        int upper = fields.values().stream().mapToInt(String::length).max().orElse(1);
        while (lower < upper) {
            int candidate = (lower + upper + 1) / 2;
            int length = fields.values().stream().mapToInt(value -> Math.min(value.length(), candidate)).sum();
            if (length <= available) {
                lower = candidate;
            } else {
                upper = candidate - 1;
            }
        }
        var content = new StringBuilder(header);
        for (var entry : fields.entrySet()) {
            content.append(entry.getKey()).append(": ").append(limit(entry.getValue(), lower)).append('\n');
        }
        return content.append(CONTENT_FOOTER).toString();
    }

    private String limit(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        int end = maxLength - 1;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end) + "…";
    }

    private String field(Object value) {
        if (value == null) {
            return "-";
        }
        String text = value.toString().replaceAll("[\\p{Cntrl}\\p{Cf}\\p{Zl}\\p{Zp}`]", " ").strip();
        int length = Math.min(text.codePointCount(0, text.length()), MAX_FIELD_LENGTH);
        return text.isEmpty() ? "-" : text.substring(0, text.offsetByCodePoints(0, length));
    }
}
