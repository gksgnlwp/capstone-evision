package com.evision.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.evision.common.error.ApiException;

class RecommendConditionTest {

    @Test
    void 기본값과_공백_정리() {
        RecommendCondition c = RecommendCondition.of(36.5, 127.3, 1, "  ", 120, null, null, " 04 ", null);

        assertThat(c.limit()).isEqualTo(RecommendCondition.DEFAULT_LIMIT);
        assertThat(c.direction()).isNull();
        assertThat(c.chargerType()).isEqualTo("04");
        assertThat(c.hasDestination()).isFalse();
    }

    @Test
    void 목적지는_두_값이_모두_있어야_한다() {
        assertThatThrownBy(() -> RecommendCondition.of(36.5, 127.3, 1, null, 120, 37.5, null, null, null))
                .isInstanceOf(ApiException.class).hasMessageContaining("destLat, destLng");
    }

    @Test
    void 남은_주행거리와_limit_범위를_검사한다() {
        assertThatThrownBy(() -> RecommendCondition.of(36.5, 127.3, 1, null, 0, null, null, null, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> RecommendCondition.of(36.5, 127.3, 1, null, 1001, null, null, null, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> RecommendCondition.of(36.5, 127.3, 1, null, 100, null, null, null, 21))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void 좌표_범위를_검사한다() {
        assertThatThrownBy(() -> RecommendCondition.of(91, 127.3, 1, null, 100, null, null, null, null))
                .isInstanceOf(ApiException.class).hasMessageContaining("현재 위치");
        assertThatThrownBy(() -> RecommendCondition.of(36.5, 127.3, 1, null, 100, 37.5, 181.0, null, null))
                .isInstanceOf(ApiException.class).hasMessageContaining("목적지");
    }
}
