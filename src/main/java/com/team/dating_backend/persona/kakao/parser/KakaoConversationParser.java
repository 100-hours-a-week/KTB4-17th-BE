package com.team.dating_backend.persona.kakao.parser;

import com.team.dating_backend.persona.kakao.dto.response.KakaoConversationParticipantResponse;
import com.team.dating_backend.persona.kakao.enums.KakaoConversationErrorCode;
import com.team.dating_backend.persona.kakao.exception.KakaoConversationBusinessException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Mirrors the receiving API's speaker boundaries. Never rewrites the uploaded
 * bytes or treats "본인" as a reserved speaker. Unsupported legacy/24-hour TXT
 * formats are rejected.
 */
@Component
public class KakaoConversationParser {

    private static final Pattern PC_DATE = Pattern.compile(
        "^-+\\s*(\\d{4})년 (\\d{1,2})월 (\\d{1,2})일 \\S+\\s*-+$", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern PC_MESSAGE = Pattern.compile(
        "^\\[([^\\]]+)\\] \\[(오전|오후) (\\d{1,2}):(\\d{2})\\] (.*)$");
    private static final String MOBILE_DATE = "(?:(\\d{4})년 (\\d{1,2})월 (\\d{1,2})일|(\\d{4})\\. (\\d{1,2})\\. (\\d{1,2})\\.)";
    private static final Pattern MOBILE_MESSAGE = Pattern.compile(
        "^" + MOBILE_DATE + " (오전|오후) (\\d{1,2}):(\\d{2}), (.+?) : (.*)$");
    private static final Pattern MOBILE_SYSTEM = Pattern.compile("^" + MOBILE_DATE);
    private static final Pattern LINES = Pattern
        .compile("\\r\\n|[\\n\\r\\u000B\\u000C\\u0085\\u2028\\u2029\\u001C-\\u001E]");
    private static final Set<String> PLACEHOLDERS = Set.of(
        "사진", "동영상", "이모티콘", "음성메시지", "삭제된 메시지입니다.");
    private static final DateTimeFormatter CSV_DATE = DateTimeFormatter
        .ofPattern("uuuu-M-d H:m:s").withResolverStyle(ResolverStyle.STRICT);

    public List<KakaoConversationParticipantResponse> parse(byte[] bytes) {
        String text = decode(bytes);
        Map<String, Integer> counts = new LinkedHashMap<>();
        try {
            if (text.startsWith("Date,User,Message")) {
                parseCsv(text, counts);
            } else {
                parseTxt(text, counts);
            }
        } catch (DateTimeException exception) {
            throw invalidExport();
        }
        if (counts.isEmpty()) {
            throw invalidExport();
        }
        return counts.entrySet().stream()
            .map(entry -> new KakaoConversationParticipantResponse(entry.getKey(), entry.getValue()))
            .toList();
    }

    public boolean isSelectable(String speaker) {
        // The receiving endpoint strips speaker_name, while TXT speaker labels stay
        // untouched.
        return speaker != null && !strip(speaker).isEmpty() && speaker.equals(strip(speaker))
            && speaker.codePointCount(0, speaker.length()) <= 40;
    }

    private String decode(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (CharacterCodingException exception) {
            throw invalidExport();
        }
    }

    private void parseCsv(String text, Map<String, Integer> counts) {
        KakaoCsvReader.read(text, header -> {
            if (!header.stream().map(this::strip).toList().equals(List.of("Date", "User", "Message"))) {
                throw invalidExport();
            }
        }, row -> {
            if (row.size() < 3) {
                return;
            }
            try {
                LocalDateTime.parse(strip(row.get(0)), CSV_DATE);
            } catch (DateTimeException exception) {
                // The receiving CSV parser skips rows with invalid dates.
                return;
            }
            count(counts, strip(row.get(1)), row.get(2));
        });
    }

    private void parseTxt(String text, Map<String, Integer> counts) {
        LocalDate pcDate = null;
        String speaker = null;
        StringBuilder content = new StringBuilder();
        for (String line : LINES.split(text, -1)) {
            Matcher date = PC_DATE.matcher(line);
            Matcher pc = PC_MESSAGE.matcher(line);
            Matcher mobile = MOBILE_MESSAGE.matcher(line);
            if (date.matches()) {
                count(counts, speaker, content.toString());
                speaker = null;
                content.setLength(0);
                pcDate = date(date, 1);
            } else if (pc.matches() && pcDate != null) {
                count(counts, speaker, content.toString());
                validateTime(pcDate, pc.group(2), pc.group(3), pc.group(4));
                speaker = pc.group(1);
                content.setLength(0);
                content.append(pc.group(5));
            } else if (mobile.matches()) {
                count(counts, speaker, content.toString());
                LocalDate day = date(mobile, mobile.group(1) != null ? 1 : 4);
                validateTime(day, mobile.group(7), mobile.group(8), mobile.group(9));
                speaker = mobile.group(10);
                content.setLength(0);
                content.append(mobile.group(11));
            } else if (MOBILE_SYSTEM.matcher(line).find()) {
                count(counts, speaker, content.toString());
                speaker = null;
                content.setLength(0);
            } else if (speaker != null) {
                content.append('\n').append(line);
            }
        }
        count(counts, speaker, content.toString());
    }

    private LocalDate date(Matcher matcher, int firstGroup) {
        return LocalDate.of(Integer.parseInt(matcher.group(firstGroup)),
            Integer.parseInt(matcher.group(firstGroup + 1)),
            Integer.parseInt(matcher.group(firstGroup + 2)));
    }

    private void validateTime(LocalDate date, String meridiem, String hour, String minute) {
        int resolvedHour = Integer.parseInt(hour) % 12 + ("오후".equals(meridiem) ? 12 : 0);
        date.atTime(resolvedHour, Integer.parseInt(minute));
    }

    private void count(Map<String, Integer> counts, String speaker, String content) {
        String cleaned = strip(content);
        if (speaker != null && !cleaned.isEmpty() && !PLACEHOLDERS.contains(cleaned)) {
            counts.merge(speaker, 1, Integer::sum);
        }
    }

    private String strip(String value) {
        // Python str.strip also includes NBSP, NEL and Unicode space separators.
        int start = 0;
        int end = value.length();
        while (start < end && isWhitespace(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && isWhitespace(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return value.substring(start, end);
    }

    private boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0x85;
    }

    private KakaoConversationBusinessException invalidExport() {
        return new KakaoConversationBusinessException(KakaoConversationErrorCode.INVALID_KAKAO_EXPORT);
    }
}
