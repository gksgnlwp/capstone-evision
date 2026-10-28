package com.evision.common.error;

import org.springframework.http.HttpStatus;

/**
 * 조회 API 공통 오류 코드 (명세 6.1).
 */
public enum ErrorCode {
    INVALID_QUERY(HttpStatus.BAD_REQUEST),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    DATA_ACCESS_FAILED(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
