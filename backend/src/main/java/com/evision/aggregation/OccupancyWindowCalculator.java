package com.evision.aggregation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 명세 5.1 30분 구간 점유율 산출 규칙. DB와 무관한 순수 계산이다.
 *
 * <pre>
 * 시점 t (충전소 단위, 급속충전기만):
 *   혼잡도(t) = CHARGING / (AVAILABLE + CHARGING)      분모 0이면 NULL
 *   가용(t)   = AVAILABLE 1기 이상 → true
 *              관측 완전(UNKNOWN 0기) + AVAILABLE 0기 → false
 *              그 외 → NULL
 * 구간:
 *   occupancy_rate  = 유효 시점 혼잡도의 평균 (유효 시점 &lt; minValidPoints 또는 값 없음 → NULL)
 *   available       = 마지막 유효 시점의 가용 값
 *   full_minutes    = 혼잡도 = 1인 유효 시점 수 × 5
 *   valid_minutes   = 유효 시점 수 × 5
 *   observed_points = 유효 시점 수
 * </pre>
 * 수집 공백(무효 시점)은 상태 유지로 채우지 않는다.
 */
public final class OccupancyWindowCalculator {

    public static final int POINTS_PER_WINDOW = 6;
    public static final int POINT_INTERVAL_MINUTES = 5;

    private OccupancyWindowCalculator() {
    }

    /** 관측 시점 1개의 급속충전기 상태 분포 */
    public record PointObservation(LocalDateTime time, boolean valid, int available, int charging,
            int unavailable, int unknown) {

        /** 분모(AVAILABLE + CHARGING)가 0이면 null */
        public Double congestion() {
            int denominator = available + charging;
            return denominator == 0 ? null : (double) charging / denominator;
        }

        public Boolean availability() {
            if (available >= 1) {
                return true;
            }
            return unknown == 0 ? Boolean.FALSE : null;
        }
    }

    public record WindowResult(BigDecimal occupancyRate, Boolean available, short fullMinutes,
            short validMinutes, short observedPoints) {
    }

    public static WindowResult calculate(List<PointObservation> points, int minValidPoints) {
        List<PointObservation> valid = points.stream().filter(PointObservation::valid).toList();
        int observed = valid.size();

        double sum = 0;
        int withValue = 0;
        int full = 0;
        for (PointObservation p : valid) {
            Double congestion = p.congestion();
            if (congestion != null) {
                sum += congestion;
                withValue++;
                if (congestion == 1.0) {
                    full++;
                }
            }
        }

        BigDecimal rate = (observed < minValidPoints || withValue == 0)
                ? null
                : BigDecimal.valueOf(sum / withValue).setScale(4, RoundingMode.HALF_UP);
        Boolean available = valid.isEmpty() ? null : valid.get(valid.size() - 1).availability();

        return new WindowResult(rate, available,
                (short) (full * POINT_INTERVAL_MINUTES),
                (short) (observed * POINT_INTERVAL_MINUTES),
                (short) observed);
    }
}
