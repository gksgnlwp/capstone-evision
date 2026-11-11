package com.evision.station.reliability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 오류 충전기 판정 기준.
 *
 * @param suspectChargingHours 충전중 상태가 이 시간보다 오래 그대로면 의심 (급속 1회 충전 중앙값 21분, 집계의 센서 멈춤 기준과 같음)
 * @param suspectAvailableDays 사용가능 상태가 이 일수보다 오래 그대로면 의심 (아무도 쓰지 않았거나 상태 전송이 멈춤)
 */
@ConfigurationProperties(prefix = "evision.reliability")
public record ReliabilityProperties(
        @DefaultValue("6") int suspectChargingHours,
        @DefaultValue("7") int suspectAvailableDays) {
}
