package com.evision.aggregation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.evision.aggregation.OccupancyWindowCalculator.PointObservation;
import com.evision.aggregation.OccupancyWindowCalculator.WindowResult;

class OccupancyWindowCalculatorTest {

    static final LocalDateTime W = LocalDateTime.of(2026, 9, 27, 10, 0);

    @Test
    void 시점_혼잡도는_충전중_나누기_가용_더하기_충전중() {
        PointObservation p = point(0, true, 1, 3, 2, 0);

        assertThat(p.congestion()).isEqualTo(0.75);   // 고장 2기는 분모에서 제외
    }

    @Test
    void 분모가_0이면_혼잡도는_NULL() {
        assertThat(point(0, true, 0, 0, 4, 0).congestion()).isNull();
    }

    @Test
    void 가용은_가용1기이상_true_관측완전_0기_false_그외_NULL() {
        assertThat(point(0, true, 1, 0, 0, 3).availability()).isTrue();
        assertThat(point(0, true, 0, 2, 1, 0).availability()).isFalse();
        assertThat(point(0, true, 0, 2, 0, 1).availability()).isNull();   // 미확인 포함, 확인된 가용 없음
    }

    @Test
    void 구간값을_유효_시점으로_계산한다() {
        List<PointObservation> points = List.of(
                point(0, true, 1, 1, 0, 0),    // 0.5
                point(5, true, 1, 1, 0, 0),    // 0.5
                point(10, true, 1, 1, 0, 0),   // 0.5
                point(15, true, 0, 2, 0, 0),   // 1.0
                point(20, true, 0, 2, 0, 0),   // 1.0
                point(25, true, 0, 2, 0, 0));  // 1.0

        WindowResult r = OccupancyWindowCalculator.calculate(points, 3);

        assertThat(r.occupancyRate()).isEqualByComparingTo(new BigDecimal("0.7500"));
        assertThat(r.available()).isFalse();            // 마지막 유효 시점
        assertThat(r.fullMinutes()).isEqualTo((short) 15);
        assertThat(r.validMinutes()).isEqualTo((short) 30);
        assertThat(r.observedPoints()).isEqualTo((short) 6);
    }

    @Test
    void 무효_시점은_상태_유지로_채우지_않는다() {
        List<PointObservation> points = List.of(
                point(0, true, 1, 1, 0, 0),
                point(5, false, 0, 2, 0, 0),    // 무효: 계산에서 빠진다
                point(10, false, 0, 2, 0, 0),
                point(15, true, 1, 1, 0, 0),
                point(20, true, 1, 1, 0, 0),
                point(25, false, 0, 2, 0, 0));  // 마지막 시점도 무효

        WindowResult r = OccupancyWindowCalculator.calculate(points, 3);

        assertThat(r.occupancyRate()).isEqualByComparingTo(new BigDecimal("0.5000"));
        assertThat(r.observedPoints()).isEqualTo((short) 3);
        assertThat(r.validMinutes()).isEqualTo((short) 15);
        assertThat(r.fullMinutes()).isZero();
        assertThat(r.available()).isTrue();    // 마지막 "유효" 시점(20분) 기준
    }

    @Test
    void 유효_시점이_최소보다_적으면_점유율은_NULL() {
        List<PointObservation> points = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            points.add(point(i * 5, i < 2, 1, 1, 0, 0));
        }

        WindowResult r = OccupancyWindowCalculator.calculate(points, 3);

        assertThat(r.occupancyRate()).isNull();
        assertThat(r.observedPoints()).isEqualTo((short) 2);
    }

    @Test
    void 유효_시점이_없으면_모두_NULL_또는_0() {
        List<PointObservation> points = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            points.add(point(i * 5, false, 1, 1, 0, 0));
        }

        WindowResult r = OccupancyWindowCalculator.calculate(points, 3);

        assertThat(r.occupancyRate()).isNull();
        assertThat(r.available()).isNull();
        assertThat(r.observedPoints()).isZero();
    }

    private static PointObservation point(int minute, boolean valid, int available, int charging, int unavailable,
            int unknown) {
        return new PointObservation(W.plusMinutes(minute), valid, available, charging, unavailable, unknown);
    }
}
