package com.team.dating_backend.file.exception;

import com.team.dating_backend.common.exception.BusinessException;
import com.team.dating_backend.file.enums.FileErrorCode;

public class FileDeleteException extends BusinessException {

    public FileDeleteException(String message, Throwable cause) {
        super(FileErrorCode.FILE_DELETE_FAILED, message, cause);
    }
}
