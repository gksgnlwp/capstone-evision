package com.evision.common.error;

import java.time.OffsetDateTime;

/**
 * 오류 응답 형식 (명세 6.1): { "code": "NOT_FOUND", "message": "...", "timestamp": "..." }
 */
public record ErrorResponse(String code, String message, OffsetDateTime timestamp) {
}
