package com.team.dating_backend.persona.kakao.service;

import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.persona.kakao.config.KakaoConversationProperties;
import com.team.dating_backend.persona.kakao.enums.KakaoConversationErrorCode;
import com.team.dating_backend.persona.kakao.exception.KakaoConversationBusinessException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
@RequiredArgsConstructor
public class KakaoConversationFileReader {

    private final KakaoConversationProperties properties;

    public byte[] read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new RequestValidationException();
        }
        String filename = filename(file);
        String lower = filename.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".txt") && !lower.endsWith(".csv")) {
            throw new KakaoConversationBusinessException(KakaoConversationErrorCode.KAKAO_FILE_TYPE_NOT_ALLOWED);
        }
        if (file.getSize() > properties.getMaxSizeBytes()) {
            throw new KakaoConversationBusinessException(KakaoConversationErrorCode.KAKAO_FILE_TOO_LARGE);
        }
        try (InputStream stream = file.getInputStream()) {
            byte[] bytes = stream.readNBytes(properties.getMaxSizeBytes() + 1);
            if (bytes.length > properties.getMaxSizeBytes()) {
                throw new KakaoConversationBusinessException(KakaoConversationErrorCode.KAKAO_FILE_TOO_LARGE);
            }
            return bytes;
        } catch (IOException exception) {
            throw new RequestValidationException();
        }
    }

    public String filename(MultipartFile file) {
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            throw new RequestValidationException();
        }
        String basename = original.replace('\\', '/');
        basename = basename.substring(basename.lastIndexOf('/') + 1);
        if (basename.isBlank() || basename.length() > 255
            || basename.codePoints().anyMatch(Character::isISOControl)) {
            throw new RequestValidationException();
        }
        return basename;
    }
}
