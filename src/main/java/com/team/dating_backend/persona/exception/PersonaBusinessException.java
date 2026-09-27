package com.team.dating_backend.persona.exception;

import com.team.dating_backend.common.exception.BusinessException;
import com.team.dating_backend.persona.enums.PersonaErrorCode;

public class PersonaBusinessException extends BusinessException {

    public PersonaBusinessException(PersonaErrorCode errorCode) {
        super(errorCode);
    }

    public PersonaBusinessException(PersonaErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
