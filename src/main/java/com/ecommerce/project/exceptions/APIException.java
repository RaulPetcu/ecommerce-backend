package com.ecommerce.project.exceptions;

import lombok.Data;

@Data
public class APIException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private ErrorCode errorCode;

    public APIException() {
    }

    public APIException(String message) {
        super(message);
        this.errorCode = ErrorCode.GENERIC;
    }

    public APIException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}