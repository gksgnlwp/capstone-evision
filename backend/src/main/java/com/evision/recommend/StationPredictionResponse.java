package com.evision.recommend;

import java.time.LocalDateTime;
import java.util.List;

import com.evision.recommend.AvailabilityPredictor.Basis;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 충전소 도착 시점 예측 (M09). 추천과 같은 계산(AvailabilityPredictor)이라 같은 충전소·도착 시각이면 추천 화면과 같은 확률이 나온다.
 *
 * @param requestedAt      계산 기준 현재 시각
 * @param comfortThreshold 쾌적 기준 사용률 (사용률이 이 값 미만이면 쾌적 = 이용 가능)
 * @param arrival          요청한 도착 시각의 예측
 * @param timeline         도착 구간부터 30분 간격 5개 (조금 늦게 도착하면 어떻게 되는지)
 * @param current          현재 급속충전기 상태 (예측이 아니라 지금 상태)
 * @param forecastMaxAgeMinutes AI 예측이 이보다 오래되면 쓰지 않고 과거 통계로 대신한다
 */
public record StationPredictionResponse(
        long stationId,
        String stationName,
        LocalDateTime requestedAt,
        double comfortThreshold,
        Point arrival,
        List<Point> timeline,
        Current current,
        long forecastMaxAgeMinutes) {

    /**
     * @param arrivalAt                도착 시각
     * @param windowStart              도착 시각이 속한 30분 구간의 시작
     * @param minutesAhead             지금부터 도착까지 분
     * @param probability              도착 시 이용 가능(쾌적) 확률 0~1. 화면에 크게 보여 줄 값
     * @param basis                    근거. AI_FORECAST 면 AI 예측, 나머지는 과거 통계 수준 (SAME_DAY_SLOT 등), PRIOR 는 이력 없음
     * @param basisSampleCount         과거 통계 근거 수준의 표본 구간 수
     * @param statisticalProbability   과거 통계만으로 낸 확률 (AI 예측과 비교용)
     * @param realtimeWeight           현재 상태를 반영한 비율 0~1. 도착이 가까울수록 크다
     * @param forecast                 이 구간에 쓴 AI 예측 (없거나 오래돼서 안 썼으면 null)
     */
    @Schema(name = "StationPredictionPoint")
    public record Point(
            LocalDateTime arrivalAt,
            LocalDateTime windowStart,
            int minutesAhead,
            double probability,
            Basis basis,
            int basisSampleCount,
            double statisticalProbability,
            double realtimeWeight,
            Forecast forecast) {
    }

    /**
     * @param probability            AI 이용 가능 확률 (p_available)
     * @param predictedOccupancyRate AI 예상 사용률 (모델이 주지 않으면 null)
     */
    @Schema(name = "StationPredictionForecast")
    public record Forecast(double probability, Double predictedOccupancyRate, String modelVersion,
            LocalDateTime generatedAt) {
    }

    /**
     * @param occupancyRate   현재 사용률 = 충전 중 / (사용 가능 + 충전 중). 판단할 수 없으면 null
     * @param lastUpdatedAt   급속충전기 상태 갱신 시각 중 가장 최근
     */
    @Schema(name = "StationPredictionCurrent")
    public record Current(int fastChargerCount, int available, int charging, int unavailable, int unknown,
            Double occupancyRate, LocalDateTime lastUpdatedAt) {
    }
}
