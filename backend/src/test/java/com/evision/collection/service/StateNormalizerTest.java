package com.evision.collection.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.evision.common.code.NormalizedStatus;
import com.evision.external.evcharger.dto.StatusItem;

class StateNormalizerTest {

    StateNormalizer normalizer = new StateNormalizer();

    Map<String, Long> chargerIds = Map.of(
            "ME000001:01", 1L,
            "ME000001:02", 2L);

    Map<String, NormalizedStatus> codes = Map.of(
            "1", NormalizedStatus.UNAVAILABLE,
            "2", NormalizedStatus.AVAILABLE,
            "3", NormalizedStatus.CHARGING);

    @Test
    void 코드표에_따라_정규화하고_시각을_파싱한다() {
        NormalizationResult result = normalizer.normalize(List.of(
                item("ME000001", "01", "2", "20260927101500"),
                item("ME000001", "02", "3", "20260927235959")), chargerIds, codes);

        assertThat(result.states()).containsExactly(
                new NormalizedState(1L, "2", NormalizedStatus.AVAILABLE, LocalDateTime.of(2026, 9, 27, 10, 15, 0)),
                new NormalizedState(2L, "3", NormalizedStatus.CHARGING, LocalDateTime.of(2026, 9, 27, 23, 59, 59)));
        assertThat(result.unknownCount()).isZero();
        assertThat(result.invalidCount()).isZero();
    }

    @Test
    void 코드표에_없는_상태값은_UNKNOWN으로_정규화하고_원천값은_유지한다() {
        NormalizationResult result = normalizer.normalize(List.of(
                item("ME000001", "01", "9", "20260927101500")), chargerIds, codes);

        assertThat(result.states()).singleElement().satisfies(s -> {
            assertThat(s.normalizedStatus()).isEqualTo(NormalizedStatus.UNKNOWN);
            assertThat(s.statusCode()).isEqualTo("9");
        });
    }

    @Test
    void 미등록_충전기는_unknown으로_건너뛴다() {
        NormalizationResult result = normalizer.normalize(List.of(
                item("EC999999", "01", "2", "20260927101500")), chargerIds, codes);

        assertThat(result.states()).isEmpty();
        assertThat(result.unknownCount()).isEqualTo(1);
        assertThat(result.invalidCount()).isZero();
    }

    @Test
    void 형식_오류는_invalid로_건너뛴다() {
        NormalizationResult result = normalizer.normalize(List.of(
                item("ME000001", "01", "2", "2026-09-27 10:15"),   // 시각 형식 오류
                item("ME000001", "01", "2", "20260231101500"),     // 존재하지 않는 날짜
                item("ME000001", "01", "22", "20260927101500"),    // stat 길이 오류
                item(null, "01", "2", "20260927101500"),           // 필수 값 누락
                item("ME000001", " ", "2", "20260927101500")),     // 공백
                chargerIds, codes);

        assertThat(result.states()).isEmpty();
        assertThat(result.invalidCount()).isEqualTo(5);
    }

    @Test
    void 앞뒤_공백은_제거하고_대조한다() {
        NormalizationResult result = normalizer.normalize(List.of(
                item(" ME000001 ", "01 ", " 2", "20260927101500")), chargerIds, codes);

        assertThat(result.states()).singleElement().extracting(NormalizedState::chargerId).isEqualTo(1L);
    }

    private static StatusItem item(String statId, String chgerId, String stat, String statUpdDt) {
        return new StatusItem("ME", statId, chgerId, stat, statUpdDt);
    }
}
