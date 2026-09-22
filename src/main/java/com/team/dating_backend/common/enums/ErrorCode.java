package com.team.dating_backend.common.enums;

import org.springframework.http.HttpStatus;

public interface ErrorCode {

    String name();

    HttpStatus status();
}
