package com.evision.common.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * /api 전체에 CORS를 연다. 공개 API가 모두 조회(GET)이고 쿠키·인증 헤더를 쓰지 않으므로 GET만 허용한다.
 * 운영자 API(/api/admin)에 인증이 붙으면(FR-38·39) 허용 메서드·헤더를 다시 정한다.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final CorsProperties properties;

    public CorsConfig(CorsProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(properties.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET")
                .maxAge(3600);
    }
}
