package com.evision.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.evision.recommend.AvailabilityPredictor.Basis;
import com.evision.recommend.AvailabilityPredictor.CurrentStatus;
import com.evision.recommend.AvailabilityPredictor.Forecast;
import com.evision.recommend.AvailabilityPredictor.Prediction;
import com.evision.recommend.AvailabilityPredictor.Settings;
import com.evision.recommend.AvailabilityPredictor.WindowSample;

class AvailabilityPredictorTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 13, 0);         // 월요일
    static final LocalDateTime ETA_MON = LocalDateTime.of(2026, 10, 5, 14, 10);    // 월 14:00 슬롯
    static final LocalDateTime ETA_SAT = LocalDateTime.of(2026, 10, 10, 14, 10);   // 토 14:00 슬롯

    final AvailabilityPredictor predictor = new AvailabilityPredictor(Settings.defaults());   // prior 0.7, k 4, tau 30

    @Test
    void 이력도_현재상태도_없으면_사전값() {
        Prediction p = predictor.predict(NOW, ETA_MON, null, List.of());

        assertThat(p.probability()).isEqualTo(0.7);
        assertThat(p.basis()).isEqualTo(Basis.PRIOR);
        assertThat(p.basisSampleCount()).isZero();
        assertThat(p.realtimeWeight()).isZero();
    }

    @Test
    void 표본이_1건이면_사전값_쪽으로_평활한다() {
        List<WindowSample> samples = List.of(sample(2026, 9, 28, 14, 0, true));   // 지난 월요일 같은 슬롯

        Prediction p = predictor.predict(NOW, ETA_MON, null, samples);

        // p4 = (1+4·0.7)/5 = 0.76, p3 = (1+4·0.76)/5 = 0.808, p2 = 0.8464, p1 = 0.87712
        assertThat(p.baseline()).isCloseTo(0.87712, within(1e-9));
        assertThat(p.basis()).isEqualTo(Basis.SAME_DAY_SLOT);
        assertThat(p.basisSampleCount()).isEqualTo(1);
    }

    @Test
    void 같은_요일_슬롯_표본이_많으면_그_값에_가까워진다() {
        List<WindowSample> samples = new ArrayList<>();
        for (int week = 0; week < 20; week++) {
            samples.add(new WindowSample(LocalDateTime.of(2026, 9, 28, 14, 0).minusWeeks(week), false));
        }

        Prediction p = predictor.predict(NOW, ETA_MON, null, samples);

        assertThat(p.baseline()).isLessThan(0.05);
    }

    @Test
    void 평일과_주말을_나눠_본다() {
        List<WindowSample> samples = List.of(
                sample(2026, 9, 26, 14, 0, true),    // 토
                sample(2026, 9, 27, 14, 0, true),    // 일
                sample(2026, 10, 3, 14, 0, true),    // 토
                sample(2026, 9, 29, 14, 0, false),   // 화
                sample(2026, 9, 30, 14, 0, false),   // 수
                sample(2026, 10, 1, 14, 0, false));  // 목

        Prediction monday = predictor.predict(NOW, ETA_MON, null, samples);
        Prediction saturday = predictor.predict(NOW, ETA_SAT, null, samples);

        assertThat(monday.basis()).isEqualTo(Basis.SAME_DAY_TYPE_SLOT);   // 월요일 표본은 없지만 평일 표본은 있다
        assertThat(saturday.basis()).isEqualTo(Basis.SAME_DAY_SLOT);
        assertThat(saturday.baseline()).isGreaterThan(monday.baseline());
    }

    @Test
    void 다른_슬롯_표본만_있으면_충전소_전체로_본다() {
        List<WindowSample> samples = List.of(
                sample(2026, 9, 28, 9, 0, false),
                sample(2026, 9, 28, 9, 30, false));

        Prediction p = predictor.predict(NOW, ETA_MON, null, samples);

        assertThat(p.basis()).isEqualTo(Basis.STATION_ALL);
        assertThat(p.baseline()).isLessThan(0.7);
    }

    @Test
    void 가용_NULL_구간은_계산에서_빠진다() {
        List<WindowSample> samples = List.of(new WindowSample(LocalDateTime.of(2026, 9, 28, 14, 0), null));

        Prediction p = predictor.predict(NOW, ETA_MON, null, samples);

        assertThat(p.basis()).isEqualTo(Basis.PRIOR);
        assertThat(p.baseline()).isEqualTo(0.7);
    }

    @Test
    void 지금_도착하면_실시간_상태를_그대로_쓴다() {
        Prediction p = predictor.predict(NOW, NOW, new CurrentStatus(1, 3, 0, 0), List.of());

        assertThat(p.realtimeWeight()).isEqualTo(1.0);
        assertThat(p.probability()).isEqualTo(1.0);
    }

    @Test
    void 도착이_멀수록_실시간_가중치가_줄어든다() {
        CurrentStatus full = new CurrentStatus(0, 4, 0, 0);   // 지금은 만차

        Prediction soon = predictor.predict(NOW, NOW.plusMinutes(30), full, List.of());
        Prediction late = predictor.predict(NOW, NOW.plusHours(3), full, List.of());

        assertThat(soon.realtimeWeight()).isCloseTo(Math.exp(-1), within(1e-9));
        assertThat(soon.probability()).isCloseTo((1 - Math.exp(-1)) * 0.7, within(1e-9));
        assertThat(late.probability()).isGreaterThan(soon.probability()).isCloseTo(0.7, within(0.01));
    }

    @Test
    void 지난_시각을_ETA로_주면_지금_도착으로_본다() {
        Prediction p = predictor.predict(NOW, NOW.minusMinutes(10), new CurrentStatus(0, 2, 0, 0), List.of());

        assertThat(p.realtimeWeight()).isEqualTo(1.0);
        assertThat(p.probability()).isZero();
    }

    @Test
    void 현재상태_판단불가면_이력만_쓴다() {
        Prediction p = predictor.predict(NOW, NOW, new CurrentStatus(0, 1, 0, 2), List.of());

        assertThat(p.realtime()).isNull();
        assertThat(p.realtimeWeight()).isZero();
        assertThat(p.probability()).isEqualTo(0.7);
    }

    @Test
    void 현재상태_가용은_가용1기이상_1_관측완전_0기_0_그외_NULL() {
        assertThat(new CurrentStatus(1, 0, 0, 3).probability()).isEqualTo(1.0);
        assertThat(new CurrentStatus(0, 2, 1, 0).probability()).isEqualTo(0.0);
        assertThat(new CurrentStatus(0, 0, 3, 0).probability()).isEqualTo(0.0);   // 전부 고장
        assertThat(new CurrentStatus(0, 2, 0, 1).probability()).isNull();
        assertThat(new CurrentStatus(0, 0, 0, 0).probability()).isNull();          // 급속충전기 없음
    }

    @Test
    void AI_예측이_있으면_과거_통계_대신_쓴다() {
        List<WindowSample> samples = List.of(sample(2026, 9, 28, 14, 0, true));   // 통계로는 0.87712

        Prediction p = predictor.predict(NOW, ETA_MON, null, samples, new Forecast(0.2, "lgbm-v1"));

        assertThat(p.basis()).isEqualTo(Basis.AI_FORECAST);
        assertThat(p.baseline()).isEqualTo(0.2);
        assertThat(p.probability()).isEqualTo(0.2);
        assertThat(p.statistical()).isCloseTo(0.87712, within(1e-9));   // 비교용으로 남는다
        assertThat(p.modelVersion()).isEqualTo("lgbm-v1");
    }

    @Test
    void AI_예측도_실시간_상태와_도착_시간으로_섞는다() {
        Prediction p = predictor.predict(NOW, NOW.plusMinutes(30), new CurrentStatus(2, 0, 0, 0), List.of(),
                new Forecast(0.4, "lgbm-v1"));

        double alpha = Math.exp(-1);
        assertThat(p.probability()).isCloseTo(alpha * 1.0 + (1 - alpha) * 0.4, within(1e-9));
    }

    @Test
    void AI_예측_확률이_범위를_벗어나면_0과_1로_자른다() {
        assertThat(predictor.predict(NOW, ETA_MON, null, List.of(), new Forecast(1.3, "v")).baseline()).isEqualTo(1.0);
        assertThat(predictor.predict(NOW, ETA_MON, null, List.of(), new Forecast(-0.1, "v")).baseline()).isZero();
    }

    @Test
    void AI_예측이_없으면_모델_버전은_NULL() {
        assertThat(predictor.predict(NOW, ETA_MON, null, List.of()).modelVersion()).isNull();
    }

    @Test
    void 슬롯은_30분_단위로_내림한다() {
        assertThat(AvailabilityPredictor.floorToSlot(LocalTime.of(10, 47))).isEqualTo(LocalTime.of(10, 30));
        assertThat(AvailabilityPredictor.floorToSlot(LocalTime.of(10, 0))).isEqualTo(LocalTime.of(10, 0));
        assertThat(AvailabilityPredictor.floorToSlot(LocalTime.of(23, 59))).isEqualTo(LocalTime.of(23, 30));
    }

    @Test
    void 잘못된_설정은_거부한다() {
        assertThatThrownBy(() -> new Settings(1.2, 4, 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(0.7, 0, 30)).isInstanceOf(IllegalArgumentException.class);
    }

    private static WindowSample sample(int y, int m, int d, int h, int min, boolean available) {
        return new WindowSample(LocalDateTime.of(y, m, d, h, min), available);
    }
}
