package com.evision.recommend;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 충전소 추천 설정.
 *
 * @param averageSpeedKmh    도착 예정 시각 계산용 평균 주행 속도. TODO(확인 필요): 시간대별 소통 정보 연동 전 임시값
 * @param roadDistanceFactor 직선거리 → 도로거리 보정 계수 (노선 이정 데이터가 없어서 근사)
 * @param historyWeeks       가용 확률 계산에 쓰는 occupancy_30m 조회 기간 (주)
 * @param forecastMaxAge     AI 예측(generated_at)이 이보다 오래되면 쓰지 않고 과거 통계로 대체한다.
 *                           TODO(확인 필요): 예측 배치 주기 확정 후 조정 (박상현 님과 합의)
 */
@ConfigurationProperties(prefix = "evision.recommend")
public record RecommendProperties(
        @DefaultValue("80") double averageSpeedKmh,
        @DefaultValue("1.3") double roadDistanceFactor,
        @DefaultValue("4") int historyWeeks,
        @DefaultValue("90m") Duration forecastMaxAge) {
}
