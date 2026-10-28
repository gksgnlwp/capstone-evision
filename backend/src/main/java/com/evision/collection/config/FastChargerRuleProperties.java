package com.evision.collection.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 급속 충전기 판정 기준. 두 조건 중 하나를 만족하면 급속이다.
 *
 * @param minOutputKw   출력이 이 값 이상이면 급속. TODO(확인 필요): 팀 기준 확정
 * @param fastTypeCodes 이 충전기 타입 코드면 급속. 비어 있으면 출력 기준만 적용한다.
 *                      TODO(확인 필요): 활용가이드 충전기 타입 코드표
 */
@ConfigurationProperties(prefix = "evision.charger.fast-rule")
public record FastChargerRuleProperties(
        Integer minOutputKw,
        @DefaultValue List<String> fastTypeCodes) {
}
