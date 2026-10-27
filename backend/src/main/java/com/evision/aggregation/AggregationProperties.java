package com.evision.aggregation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 30분 점유율 집계 설정 (명세 5.2).
 *
 * @param minValidPoints 유효 관측 시점이 이보다 적으면 occupancy_rate = NULL. TODO(확인 필요): 박상현 님과 합의
 * @param interpolate    수집 공백 보간 여부. 보간 금지가 기본값이며, 현재 true는 지원하지 않는다
 * @param backfillDays   0보다 크면 앱 시작 시 최근 N일의 구간을 다시 집계한다 (매핑을 새로 적재한 뒤 사용)
 * @param stuckChargingHours 충전중 상태가 이 시간보다 오래 이어지면 센서 멈춤으로 보고 미확인으로 센다
 *                       (급속 1회 충전 중앙값 21분, 2026-10-26 점검에서 7일 넘게 충전중인 급속 23대 발견)
 */
@ConfigurationProperties(prefix = "evision.aggregation")
public record AggregationProperties(
        String cron,
        @DefaultValue("3") int minValidPoints,
        @DefaultValue("false") boolean interpolate,
        @DefaultValue("0") int backfillDays,
        @DefaultValue("6") int stuckChargingHours) {
}
