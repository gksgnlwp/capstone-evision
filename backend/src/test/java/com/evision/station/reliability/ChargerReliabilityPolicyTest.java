package com.evision.station.reliability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.evision.common.code.NormalizedStatus;

class ChargerReliabilityPolicyTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 12, 0);

    ChargerReliabilityPolicy policy = new ChargerReliabilityPolicy(new ReliabilityProperties(6, 7));

    @Test
    void 충전중이_6시간을_넘으면_의심() {
        assertThat(policy.classify(NormalizedStatus.CHARGING, NOW.minusHours(6), NOW)).isEqualTo(ChargerReliability.NORMAL);
        assertThat(policy.classify(NormalizedStatus.CHARGING, NOW.minusHours(6).minusMinutes(1), NOW))
                .isEqualTo(ChargerReliability.SUSPECT);
    }

    @Test
    void 사용가능이_7일을_넘으면_의심() {
        assertThat(policy.classify(NormalizedStatus.AVAILABLE, NOW.minusDays(7), NOW)).isEqualTo(ChargerReliability.NORMAL);
        assertThat(policy.classify(NormalizedStatus.AVAILABLE, NOW.minusDays(7).minusMinutes(1), NOW))
                .isEqualTo(ChargerReliability.SUSPECT);
    }

    @Test
    void 사용_불가는_고장_점검이고_상태를_모르면_확인_불가() {
        assertThat(policy.classify(NormalizedStatus.UNAVAILABLE, NOW, NOW)).isEqualTo(ChargerReliability.FAULT);
        assertThat(policy.classify(NormalizedStatus.UNKNOWN, NOW, NOW)).isEqualTo(ChargerReliability.UNKNOWN);
        assertThat(policy.classify(null, null, NOW)).isEqualTo(ChargerReliability.UNKNOWN);
    }

    @Test
    void 상태는_있는데_갱신_시각을_모르면_의심() {
        assertThat(policy.classify(NormalizedStatus.AVAILABLE, null, NOW)).isEqualTo(ChargerReliability.SUSPECT);
    }

    @Test
    void 충전소는_정상_대수로_나눈다() {
        assertThat(StationReliability.of(2, 2)).isEqualTo(StationReliability.NORMAL);
        assertThat(StationReliability.of(1, 2)).isEqualTo(StationReliability.PARTIAL);
        assertThat(StationReliability.of(0, 2)).isEqualTo(StationReliability.ALL_ERROR);
    }
}
