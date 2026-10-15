package com.team.dating_backend.profiletrust.service;

import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.profiletrust.enums.ProfileTrustErrorCode;
import com.team.dating_backend.profiletrust.exception.ProfileTrustBusinessException;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class VerificationVideoReader {

    public static final int MAX_VIDEO_BYTES = 5 * 1024 * 1024;
    private static final Set<String> SUPPORTED_TYPES = Set.of("video/mp4", "video/webm", "video/quicktime");

    public record Video(byte[] bytes, String contentType, String hash) {}

    public Video read(MultipartFile video) {
        if (video == null || video.isEmpty()) {
            throw new RequestValidationException();
        }
        String contentType;
        try {
            String suppliedType = video.getContentType() == null ? "" : video.getContentType();
            MediaType type = MediaType.parseMediaType(suppliedType.split(";", 2)[0].strip());
            contentType = (type.getType() + "/" + type.getSubtype()).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw new ProfileTrustBusinessException(ProfileTrustErrorCode.VIDEO_TYPE_NOT_ALLOWED);
        }
        if (!SUPPORTED_TYPES.contains(contentType)) {
            throw new ProfileTrustBusinessException(ProfileTrustErrorCode.VIDEO_TYPE_NOT_ALLOWED);
        }
        if (video.getSize() > MAX_VIDEO_BYTES) {
            throw new ProfileTrustBusinessException(ProfileTrustErrorCode.VIDEO_TOO_LARGE);
        }
        try (InputStream stream = video.getInputStream()) {
            byte[] bytes = stream.readNBytes(MAX_VIDEO_BYTES + 1);
            if (bytes.length > MAX_VIDEO_BYTES) {
                throw new ProfileTrustBusinessException(ProfileTrustErrorCode.VIDEO_TOO_LARGE);
            }
            if (bytes.length == 0) {
                throw new RequestValidationException();
            }
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new Video(bytes, contentType, hash);
        } catch (IOException exception) {
            throw new ProfileTrustBusinessException(ProfileTrustErrorCode.VIDEO_READ_FAILED, exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new ProfileTrustBusinessException(ProfileTrustErrorCode.VERIFICATION_FAILED, exception);
        }
    }
}
