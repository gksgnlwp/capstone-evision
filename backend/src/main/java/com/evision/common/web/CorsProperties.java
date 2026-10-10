package com.evision.common.web;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 브라우저(프론트)에서 /api를 호출할 때의 CORS 허용 출처.
 *
 * @param allowedOrigins 허용할 출처 목록 (예: http://localhost:5173). 비우면 다른 출처의 브라우저 호출을 막는다.
 *                       운영 서버는 환경변수 EVISION_CORS_ALLOWED_ORIGINS에 쉼표로 구분해 넣는다
 */
@ConfigurationProperties(prefix = "evision.cors")
public record CorsProperties(@DefaultValue List<String> allowedOrigins) {
}
