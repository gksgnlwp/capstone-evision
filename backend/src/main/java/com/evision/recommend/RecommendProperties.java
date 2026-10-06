package com.evision.recommend;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 충전소 추천 설정.
 *
 * @param averageSpeedKmh    도착 예정 시각 계산용 평균 주행 속도. TODO(확인 필요): 시간대별 소통 정보 연동 전 임시값
 * @param roadDistanceFactor 직선거리 → 도로거리 보정 계수 (노선 이정 데이터가 없어서 근사)
 * @param historyWeeks       가용 확률 계산에 쓰는 occupancy_30m 조회 기간 (주)
 */
@ConfigurationProperties(prefix = "evision.recommend")
public record RecommendProperties(
        @DefaultValue("80") double averageSpeedKmh,
        @DefaultValue("1.3") double roadDistanceFactor,
        @DefaultValue("4") int historyWeeks) {
}
