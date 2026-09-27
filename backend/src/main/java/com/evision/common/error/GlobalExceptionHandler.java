package com.evision.common.error;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 조회 API 공통 오류 처리 (명세 6.1). 0건은 오류가 아니라 200 + 빈 목록으로 응답한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        return respond(e.code(), e.getMessage());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleBadParameter(Exception e) {
        String message = e instanceof MissingServletRequestParameterException m
                ? "필수 파라미터가 없습니다: " + m.getParameterName()
                : "파라미터 형식이 올바르지 않습니다: " + ((MethodArgumentTypeMismatchException) e).getName();
        return respond(ErrorCode.INVALID_QUERY, message);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccess(DataAccessException e) {
        log.error("데이터 조회 실패", e);
        return respond(ErrorCode.DATA_ACCESS_FAILED, "데이터를 조회하지 못했습니다.");
    }

    private ResponseEntity<ErrorResponse> respond(ErrorCode code, String message) {
        return ResponseEntity.status(code.status())
                .body(new ErrorResponse(code.name(), message, OffsetDateTime.now(clock)));
    }
}
