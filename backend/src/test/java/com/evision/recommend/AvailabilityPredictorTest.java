package com.evision.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.evision.recommend.AvailabilityPredictor.Basis;
import com.evision.recommend.AvailabilityPredictor.Count;
import com.evision.recommend.AvailabilityPredictor.CurrentStatus;
import com.evision.recommend.AvailabilityPredictor.Forecast;
import com.evision.recommend.AvailabilityPredictor.HistoryStats;
import com.evision.recommend.AvailabilityPredictor.SlotCount;
import com.evision.recommend.AvailabilityPredictor.Prediction;
import com.evision.recommend.AvailabilityPredictor.Settings;
import com.evision.recommend.AvailabilityPredictor.WindowSample;

class AvailabilityPredictorTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 13, 0);         // 월요일
    static final LocalDateTime ETA_MON = LocalDateTime.of(2026, 10, 5, 14, 10);    // 월 14:00 슬롯
    static final LocalDateTime ETA_SAT = LocalDateTime.of(2026, 10, 10, 14, 10);   // 토 14:00 슬롯

    final AvailabilityPredictor predictor = new AvailabilityPredictor(Settings.defaults());   // prior 0.7, k 4, tau 30, 쾌적 0.5

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
            samples.add(new WindowSample(LocalDateTime.of(2026, 9, 28, 14, 0).minusWeeks(week), 0.8));
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
    void 사용률_NULL_구간은_계산에서_빠진다() {
        List<WindowSample> samples = List.of(new WindowSample(LocalDateTime.of(2026, 9, 28, 14, 0), null));

        Prediction p = predictor.predict(NOW, ETA_MON, null, samples);

        assertThat(p.basis()).isEqualTo(Basis.PRIOR);
        assertThat(p.baseline()).isEqualTo(0.7);
    }

    @Test
    void 지금_도착하면_실시간_상태를_그대로_쓴다() {
        Prediction p = predictor.predict(NOW, NOW, new CurrentStatus(3, 1, 0, 0), List.of());   // 사용률 25%

        assertThat(p.realtimeWeight()).isEqualTo(1.0);
        assertThat(p.probability()).isEqualTo(1.0);
    }

    @Test
    void 도착이_멀수록_실시간_가중치가_줄어든다() {
        CurrentStatus full = new CurrentStatus(0, 4, 0, 0);   // 지금은 사용률 100%

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
        Prediction p = predictor.predict(NOW, NOW, new CurrentStatus(0, 0, 1, 2), List.of());   // 전부 고장·미확인

        assertThat(p.realtime()).isNull();
        assertThat(p.realtimeWeight()).isZero();
        assertThat(p.probability()).isEqualTo(0.7);
    }

    @Test
    void 현재상태는_사용률이_기준_미만이면_1_이상이면_0_분모가_없으면_NULL() {
        assertThat(new CurrentStatus(1, 0, 0, 3).probability(0.5)).isEqualTo(1.0);   // 미확인은 분모에서 뺀다 → 0%
        assertThat(new CurrentStatus(3, 2, 0, 0).probability(0.5)).isEqualTo(1.0);   // 40%
        assertThat(new CurrentStatus(1, 1, 0, 0).probability(0.5)).isEqualTo(0.0);   // 50%는 쾌적 아님 (미만 기준)
        assertThat(new CurrentStatus(0, 2, 5, 0).probability(0.5)).isEqualTo(0.0);   // 고장은 분모에서 뺀다 → 100%
        assertThat(new CurrentStatus(0, 0, 3, 0).probability(0.5)).isNull();          // 전부 고장
        assertThat(new CurrentStatus(0, 0, 0, 2).probability(0.5)).isNull();          // 전부 미확인
        assertThat(new CurrentStatus(0, 0, 0, 0).probability(0.5)).isNull();          // 급속충전기 없음
        assertThat(new CurrentStatus(1, 1, 0, 0).probability(0.8)).isEqualTo(1.0);   // 기준을 바꾸면 판정도 바뀐다
    }

    @Test
    void 현재_사용률은_충전중_나누기_사용가능더하기_충전중() {
        assertThat(new CurrentStatus(1, 3, 2, 1).occupancyRate()).isEqualTo(0.75);
        assertThat(new CurrentStatus(0, 0, 2, 1).occupancyRate()).isNull();
    }

    @Test
    void 과거_구간은_사용률이_기준_미만이면_쾌적으로_센다() {
        List<WindowSample> samples = List.of(
                new WindowSample(LocalDateTime.of(2026, 9, 28, 14, 0), 0.0),
                new WindowSample(LocalDateTime.of(2026, 9, 21, 14, 0), 0.49),
                new WindowSample(LocalDateTime.of(2026, 9, 14, 14, 0), 0.5),    // 기준과 같으면 쾌적 아님
                new WindowSample(LocalDateTime.of(2026, 9, 7, 14, 0), 1.0));

        assertThat(HistoryStats.from(ETA_MON, samples, 0.5).sameDay()).isEqualTo(new Count(4, 2));
        assertThat(HistoryStats.from(ETA_MON, samples, 0.8).sameDay()).isEqualTo(new Count(4, 3));
    }

    @Test
    void 설정한_쾌적_기준으로_예측한다() {
        AvailabilityPredictor loose = new AvailabilityPredictor(Settings.withComfortThreshold(0.8));
        CurrentStatus halfUsed = new CurrentStatus(2, 2, 0, 0);   // 사용률 50%

        assertThat(predictor.predict(NOW, NOW, halfUsed, List.of()).probability()).isZero();
        assertThat(loose.predict(NOW, NOW, halfUsed, List.of()).probability()).isEqualTo(1.0);
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
    void DB에서_미리_센_값과_구간_목록으로_센_값이_같다() {
        List<WindowSample> samples = List.of(
                sample(2026, 9, 28, 14, 0, false),   // 월 같은 슬롯
                sample(2026, 9, 21, 14, 0, true),    // 월 같은 슬롯
                sample(2026, 9, 29, 14, 0, true),    // 화 같은 슬롯
                sample(2026, 9, 26, 14, 0, false),   // 토 같은 슬롯
                sample(2026, 9, 28, 9, 30, true),    // 월 다른 슬롯
                new WindowSample(LocalDateTime.of(2026, 9, 28, 14, 0).minusWeeks(2), null));   // 제외
        List<SlotCount> aggregates = List.of(
                new SlotCount(DayOfWeek.MONDAY, true, 2, 1),
                new SlotCount(DayOfWeek.TUESDAY, true, 1, 1),
                new SlotCount(DayOfWeek.SATURDAY, true, 1, 0),
                new SlotCount(DayOfWeek.MONDAY, false, 1, 1));

        HistoryStats fromSamples = HistoryStats.from(ETA_MON, samples, 0.5);

        assertThat(HistoryStats.fromAggregates(ETA_MON, aggregates)).isEqualTo(fromSamples);
        assertThat(fromSamples.all().n()).isEqualTo(5);
        assertThat(fromSamples.sameSlot().n()).isEqualTo(4);
        assertThat(fromSamples.sameDayType().n()).isEqualTo(3);   // 토요일 제외
        assertThat(fromSamples.sameDay().n()).isEqualTo(2);
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
        assertThatThrownBy(() -> Settings.withComfortThreshold(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Settings.withComfortThreshold(1.1)).isInstanceOf(IllegalArgumentException.class);
    }

    /** comfortable 이면 사용률 0.2, 아니면 0.8 (기준 0.5) */
    private static WindowSample sample(int y, int m, int d, int h, int min, boolean comfortable) {
        return new WindowSample(LocalDateTime.of(y, m, d, h, min), comfortable ? 0.2 : 0.8);
    }
}
