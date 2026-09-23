package com.team.dating_backend.file.exception;

import com.team.dating_backend.common.exception.BusinessException;
import com.team.dating_backend.file.enums.FileErrorCode;

public class FileUploadException extends BusinessException {

    public FileUploadException(String message, Throwable cause) {
        super(FileErrorCode.FILE_UPLOAD_FAILED, message, cause);
    }
}
