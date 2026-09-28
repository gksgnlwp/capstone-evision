package com.evision.reference;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 기준 데이터 적재 설정 (명세 4.7).
 *
 * @param loadOnStartup true면 앱 시작 시 기준 데이터와 매핑을 적재한다
 * @param location      CSV 위치. route.csv, rest_area.csv, interchange.csv, station_access.csv
 */
@ConfigurationProperties(prefix = "evision.reference")
public record ReferenceProperties(
        @DefaultValue("false") boolean loadOnStartup,
        @DefaultValue("classpath:reference/") String location) {
}
