package com.team.dating_backend.file.exception;

import com.team.dating_backend.common.exception.BusinessException;
import com.team.dating_backend.file.enums.FileErrorCode;

public class FileNotFoundException extends BusinessException {

    public FileNotFoundException(Long fileId) {
        super(FileErrorCode.FILE_NOT_FOUND, "조회 가능한 파일이 없습니다. fileId=" + fileId);
    }
}
