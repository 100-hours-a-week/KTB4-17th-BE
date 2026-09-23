package com.team.dating_backend.file.exception;

import com.team.dating_backend.common.exception.BusinessException;
import com.team.dating_backend.file.enums.FileErrorCode;

public class FileReadException extends BusinessException {

    public FileReadException(String message, Throwable cause) {
        super(FileErrorCode.FILE_READ_URL_FAILED, message, cause);
    }
}
