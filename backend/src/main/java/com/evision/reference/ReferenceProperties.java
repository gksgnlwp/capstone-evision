package com.evision.reference;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 기준 데이터 적재 설정 (명세 4.7).
 *
 * @param loadOnStartup true면 앱 시작 시 기준 데이터와 매핑을 적재한다
 * @param location      CSV 위치. route.csv, rest_area.csv, interchange.csv, station_access.csv
 * @param icAutoMapping        true면 적재할 때 IC 인근 충전소 자동 매핑(AUTO)을 다시 만든다 (FR-46)
 * @param icRadiusKm           IC에서 충전소까지 최대 직선거리
 * @param icMaxPerIc           IC 한 곳에 매핑할 최대 충전소 수 (가까운 순)
 * @param icExcludeNamePattern 이름이 이 정규식에 맞으면 제외 (공동주택 등, 대소문자 무시)
 */
@ConfigurationProperties(prefix = "evision.reference")
public record ReferenceProperties(
        @DefaultValue("false") boolean loadOnStartup,
        @DefaultValue("classpath:reference/") String location,
        @DefaultValue("true") boolean icAutoMapping,
        @DefaultValue("2.0") double icRadiusKm,
        @DefaultValue("3") int icMaxPerIc,
        @DefaultValue("아파트|APT|빌라|오피스텔") String icExcludeNamePattern) {
}
