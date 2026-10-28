package com.evision.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.evision.recommend.RecommendScorer.Candidate;
import com.evision.recommend.RecommendScorer.Result;
import com.evision.recommend.RecommendScorer.Scored;
import com.evision.recommend.RecommendScorer.Settings;
import com.evision.recommend.RecommendScorer.Weights;

class RecommendScorerTest {

    final RecommendScorer scorer = new RecommendScorer(Settings.defaults());   // 안전비율 0.8

    @Test
    void 안전비율을_넘는_거리는_제외한다() {
        Candidate near = candidate(1, 50, 0, 0.5);
        Candidate edge = candidate(2, 79, 1, 0.5);   // 80 = 100 × 0.8 → 포함
        Candidate far = candidate(3, 80, 0.5, 0.9);  // 80.5 → 제외

        Result r = scorer.rank(List.of(near, edge, far), 100, 10);

        assertThat(r.ranked()).extracting(s -> s.candidate().stationId()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(r.unreachable()).containsExactly(far);
    }

    @Test
    void 가용_확률이_높은_곳이_위로_온다() {
        Result r = scorer.rank(List.of(candidate(1, 30, 0, 0.2), candidate(2, 30, 0, 0.9)), 100, 10);

        assertThat(r.ranked()).extracting(s -> s.candidate().stationId()).containsExactly(2L, 1L);
    }

    @Test
    void 다른_조건이_같으면_우회가_짧은_곳이_위로_온다() {
        Result r = scorer.rank(List.of(candidate(1, 30, 3, 0.8), candidate(2, 27, 0, 0.8)), 100, 10);

        assertThat(r.ranked().get(0).candidate().stationId()).isEqualTo(2L);
    }

    @Test
    void 항목_점수를_계산한다() {
        Candidate c = new Candidate(1, 38, 2, 0.6, 100, 2);

        Scored s = scorer.rank(List.of(c), 100, 10).ranked().get(0);

        assertThat(s.breakdown().availability()).isEqualTo(0.6);
        assertThat(s.breakdown().detour()).isCloseTo(0.6, within(1e-9));     // 1 - 2/5
        assertThat(s.breakdown().power()).isCloseTo(0.5, within(1e-9));      // 100/200
        assertThat(s.breakdown().capacity()).isCloseTo(0.5, within(1e-9));   // 2/(2+2)
        assertThat(s.breakdown().progress()).isCloseTo(0.5, within(1e-9));   // 40/80
        // 0.55·0.6 + 0.15·0.6 + 0.1·0.5 + 0.1·0.5 + 0.1·0.5 = 0.57
        assertThat(s.score()).isCloseTo(0.57, within(1e-9));
    }

    @Test
    void 출력_미상이면_power는_0_우회가_최대를_넘으면_detour는_0() {
        Candidate c = new Candidate(1, 10, 8, 0.5, null, 0);

        Scored s = scorer.rank(List.of(c), 100, 10).ranked().get(0);

        assertThat(s.breakdown().power()).isZero();
        assertThat(s.breakdown().detour()).isZero();
        assertThat(s.breakdown().capacity()).isZero();
    }

    @Test
    void 동점이면_가까운_순_그다음_stationId_순() {
        Weights onlyAvailability = new Weights(1, 0, 0, 0, 0);
        RecommendScorer s = new RecommendScorer(new Settings(0.8, 5, 200, 2, onlyAvailability));

        Result r = s.rank(List.of(candidate(3, 40, 0, 0.5), candidate(2, 20, 0, 0.5), candidate(1, 40, 0, 0.5)),
                100, 10);

        assertThat(r.ranked()).extracting(x -> x.candidate().stationId()).containsExactly(2L, 1L, 3L);
    }

    @Test
    void limit만큼만_반환한다() {
        Result r = scorer.rank(List.of(candidate(1, 10, 0, 0.1), candidate(2, 10, 0, 0.9), candidate(3, 10, 0, 0.5)),
                100, 2);

        assertThat(r.ranked()).extracting(x -> x.candidate().stationId()).containsExactly(2L, 3L);
    }

    @Test
    void 남은_주행거리가_0이면_모두_제외한다() {
        Result r = scorer.rank(List.of(candidate(1, 0, 0, 1.0)), 0, 10);

        assertThat(r.ranked()).isEmpty();
        assertThat(r.unreachable()).hasSize(1);
    }

    @Test
    void 잘못된_설정은_거부한다() {
        assertThatThrownBy(() -> new Weights(0, 0, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Weights(-1, 1, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(1.5, 5, 200, 2, Weights.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Candidate candidate(long id, double distanceKm, double detourKm, double availability) {
        return new Candidate(id, distanceKm, detourKm, availability, 100, 2);
    }
}
