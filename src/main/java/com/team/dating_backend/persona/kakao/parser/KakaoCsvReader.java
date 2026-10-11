package com.team.dating_backend.persona.kakao.parser;

import com.team.dating_backend.persona.kakao.enums.KakaoConversationErrorCode;
import com.team.dating_backend.persona.kakao.exception.KakaoConversationBusinessException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reads quoted CSV fields, including embedded newlines, without splitting
 * message contents.
 */
final class KakaoCsvReader {

    private KakaoCsvReader() {}

    static void read(String text, Consumer<List<String>> onHeader, Consumer<List<String>> onRow) {
        boolean firstRow = true;
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean closedQuote = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (quoted) {
                if (character == '"') {
                    if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
                        field.append('"');
                        index++;
                    } else {
                        quoted = false;
                        closedQuote = true;
                    }
                } else {
                    field.append(character);
                }
                continue;
            }
            if (character == ',' || character == '\n' || character == '\r') {
                row.add(field.toString());
                field.setLength(0);
                closedQuote = false;
                if (character != ',') {
                    (firstRow ? onHeader : onRow).accept(List.copyOf(row));
                    firstRow = false;
                    row.clear();
                    if (character == '\r' && index + 1 < text.length()
                        && text.charAt(index + 1) == '\n') {
                        index++;
                    }
                }
            } else if (closedQuote) {
                throw invalidExport();
            } else if (character == '"' && field.isEmpty()) {
                quoted = true;
            } else {
                field.append(character);
            }
        }
        if (quoted) {
            throw invalidExport();
        }
        if (!row.isEmpty() || !field.isEmpty() || closedQuote) {
            row.add(field.toString());
            (firstRow ? onHeader : onRow).accept(List.copyOf(row));
        }
    }

    private static KakaoConversationBusinessException invalidExport() {
        return new KakaoConversationBusinessException(KakaoConversationErrorCode.INVALID_KAKAO_EXPORT);
    }
}
