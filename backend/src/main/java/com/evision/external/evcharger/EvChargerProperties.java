package com.evision.external.evcharger;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 한국환경공단 전기자동차 충전소 정보 API 설정.
 * serviceKey는 환경변수 DATA_GO_KR_SERVICE_KEY로만 주입하고, 로그에 남기지 않는다.
 */
@ConfigurationProperties(prefix = "evision.evcharger")
public record EvChargerProperties(
        String baseUrl,
        String serviceKey,
        @DefaultValue("9999") int pageSize,
        @DefaultValue("10") int statusPeriodMinutes,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("60s") Duration readTimeout,
        @DefaultValue("3") int maxRetries,
        @DefaultValue("2s") Duration retryInitialBackoff,
        @DefaultValue("4m") Duration statusTimeBudget,
        @DefaultValue("950") int dailyCallLimit) {

    public boolean hasServiceKey() {
        return serviceKey != null && !serviceKey.isBlank();
    }

    /** toString에 인증키가 찍히지 않게 가린다. */
    @Override
    public String toString() {
        return "EvChargerProperties[baseUrl=" + baseUrl + ", serviceKey=***, pageSize=" + pageSize
                + ", statusPeriodMinutes=" + statusPeriodMinutes + ", maxRetries=" + maxRetries
                + ", dailyCallLimit=" + dailyCallLimit + "]";
    }
}
