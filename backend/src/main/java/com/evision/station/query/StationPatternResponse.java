package com.evision.station.query;

import java.time.LocalDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 충전소 요일·시간대별 혼잡 패턴 (최근 N주 30분 집계).
 *
 * @param from             집계에 쓴 구간 시작 (포함, 30분 정각)
 * @param to               집계에 쓴 구간 끝 (제외, 지금 진행 중인 30분 구간의 시작)
 * @param comfortThreshold 쾌적 기준 사용률. 추천·AI 예측과 같은 값이다.
 * @param totalWindows     기간 안의 30분 구간 수
 * @param validWindows     사용률을 알 수 있는 구간 수 (평균에 쓴 구간)
 * @param excludedWindows  사용률을 알 수 없어 뺀 구간 수 (수집 누락, 급속 충전기가 모두 고장·점검·미확인이던 구간)
 * @param slots            월요일 00:00부터 일요일 23:30까지 7 × 48 = 336칸. 표본이 없는 칸도 samples=0으로 나온다.
 */
public record StationPatternResponse(
        long stationId,
        String stationName,
        int weeks,
        LocalDateTime from,
        LocalDateTime to,
        double comfortThreshold,
        int totalWindows,
        int validWindows,
        int excludedWindows,
        List<Slot> slots) {

    /**
     * @param dayOfWeek        월=1 … 일=7 (ISO)
     * @param time             30분 칸 시작 시각 "HH:mm"
     * @param averageOccupancyRate 평균 사용률 0~1 (표본이 없으면 null)
     * @param comfortRate      쾌적(사용률 &lt; comfortThreshold)이던 구간 비율 0~1 (표본이 없으면 null)
     * @param samples          평균에 쓴 구간 수 (최대 주 수)
     */
    public record Slot(
            @Schema(description = "월=1 … 일=7") int dayOfWeek,
            @Schema(example = "18:30") String time,
            Double averageOccupancyRate,
            Double comfortRate,
            int samples) {
    }
}
