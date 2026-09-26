package com.team.dating_backend.file.exception;

import com.team.dating_backend.common.exception.BusinessException;
import com.team.dating_backend.file.enums.FileErrorCode;

public class FileBusinessException extends BusinessException {

    public FileBusinessException(FileErrorCode errorCode) {
        super(errorCode);
    }

    public FileBusinessException(FileErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
