package com.team.dating_backend.file.exception;

import com.team.dating_backend.file.enums.FileErrorCode;

public class FileStorageException extends RuntimeException {

    private final FileErrorCode errorCode;

    public FileStorageException(FileErrorCode errorCode, Throwable cause) {
        super(null, cause);
        this.errorCode = errorCode;
    }

    public FileErrorCode getErrorCode() {
        return errorCode;
    }
}
