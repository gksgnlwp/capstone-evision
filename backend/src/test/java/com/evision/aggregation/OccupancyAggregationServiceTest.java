package com.evision.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.evision.TestcontainersConfiguration;
import com.evision.support.TestDb;

/**
 * 고정 이력 fixture로 30분 집계를 검증한다. 구간 [10:00, 10:30), 관측 시점 10:00 ~ 10:25.
 *
 * <pre>
 * 충전기 A(급속): 09:50 사용가능(2) → 10:12 충전중(3)
 * 충전기 B(급속): 09:58 충전중(3)
 * 충전기 C(완속): 09:58 충전중(3)   ← 급속이 아니라 집계에서 빠진다
 * 시점별 혼잡도: 10:00·05·10 = 0.5 / 10:15·20·25 = 1.0
 * </pre>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OccupancyAggregationServiceTest {

    static final LocalDateTime W = LocalDateTime.of(2026, 9, 27, 10, 0);

    @Autowired
    OccupancyAggregationService service;
    @Autowired
    JdbcTemplate jdbc;

    TestDb db;
    long stationId;
    long chargerA;
    long chargerB;
    long runId;

    @BeforeEach
    void setUp() {
        db = new TestDb(jdbc);
        db.clear();
        stationId = db.station("ME000001");
        db.mapToRestArea(stationId);
        chargerA = db.charger(stationId, "01", true);
        chargerB = db.charger(stationId, "02", true);
        long chargerC = db.charger(stationId, "03", false);

        // 매핑되지 않은 충전소: 집계 대상이 아니다
        long unmapped = db.station("ME000002");
        db.charger(unmapped, "01", true);

        for (LocalDateTime t = W.minusMinutes(10); !t.isAfter(W.plusMinutes(30)); t = t.plusMinutes(5)) {
            runId = db.statusRun(t, "SUCCESS");
        }
        db.history(chargerA, "2", W.minusMinutes(10), runId);
        db.history(chargerB, "3", W.minusMinutes(2), runId);
        db.history(chargerC, "3", W.minusMinutes(2), runId);
        db.history(chargerA, "3", W.plusMinutes(12), runId);
    }

    @Test
    void 매핑된_충전소의_급속충전기로_구간값을_계산한다() {
        int saved = service.aggregateWindow(W);

        assertThat(saved).isEqualTo(1);
        assertThat(row()).containsEntry("fast_charger_count", 2)
                .containsEntry("available", false)
                .containsEntry("full_minutes", 15)
                .containsEntry("valid_minutes", 30)
                .containsEntry("observed_points", 6);
        assertThat((BigDecimal) row().get("occupancy_rate")).isEqualByComparingTo("0.7500");
    }

    @Test
    void 수집_공백_시점은_무효로_처리하고_상태_유지로_채우지_않는다() {
        // 10:05, 10:10, 10:15 회차가 없다 → 10:10, 10:15 시점은 직전 10분 안에 성공 회차가 없어 무효
        jdbc.update("DELETE FROM collection_run WHERE started_at IN (?, ?, ?) AND run_id <> ?",
                W.plusMinutes(5), W.plusMinutes(10), W.plusMinutes(15), runId);

        service.aggregateWindow(W);

        // 유효 시점: 10:00(0.5) 10:05(0.5) 10:20(1.0) 10:25(1.0)
        assertThat(row()).containsEntry("observed_points", 4)
                .containsEntry("valid_minutes", 20)
                .containsEntry("full_minutes", 10);
        assertThat((BigDecimal) row().get("occupancy_rate")).isEqualByComparingTo("0.7500");
    }

    @Test
    void 실패_회차는_유효성에_쓰지_않고_PARTIAL은_쓴다() {
        jdbc.update("UPDATE collection_run SET run_status = 'FAILED' WHERE started_at IN (?, ?)",
                W.plusMinutes(5), W.plusMinutes(10));
        jdbc.update("UPDATE collection_run SET run_status = 'PARTIAL' WHERE started_at = ?", W.plusMinutes(15));

        service.aggregateWindow(W);

        // 10:10 시점만 무효 ((10:00, 10:11] 안에 성공 회차 없음). 10:15는 PARTIAL로 유효
        assertThat(row()).containsEntry("observed_points", 5);
    }

    @Test
    void 분모가_0이면_점유율은_NULL이고_관측이_완전하면_가용은_false() {
        db.history(chargerA, "4", W.minusMinutes(1), runId);   // 운영중지
        db.history(chargerB, "5", W.minusMinutes(1), runId);   // 점검중
        jdbc.update("DELETE FROM charger_status_history WHERE charger_id = ? AND status_updated_at = ?",
                chargerA, W.plusMinutes(12));

        service.aggregateWindow(W);

        assertThat(row()).containsEntry("occupancy_rate", null)
                .containsEntry("available", false)
                .containsEntry("observed_points", 6);
    }

    @Test
    void 이력이_없는_충전기는_미확인이라_가용을_단정하지_않는다() {
        jdbc.update("DELETE FROM charger_status_history WHERE charger_id = ?", chargerA);

        service.aggregateWindow(W);

        // B만 확인됨(충전중) → 혼잡도 1.0, A 미확인이라 가용 NULL
        assertThat((BigDecimal) row().get("occupancy_rate")).isEqualByComparingTo("1.0000");
        assertThat(row()).containsEntry("available", null);
    }

    @Test
    void 같은_구간을_다시_집계해도_결과가_같다() {
        service.aggregateWindow(W);
        Map<String, Object> first = row();

        service.aggregateWindow(W);

        assertThat(db.count("occupancy_30m")).isEqualTo(1);
        Map<String, Object> second = row();
        for (String col : List.of("occupancy_rate", "available", "full_minutes", "valid_minutes", "observed_points")) {
            assertThat(second.get(col)).as(col).isEqualTo(first.get(col));
        }
    }

    @Test
    void 매핑이_없으면_아무것도_저장하지_않는다() {
        jdbc.update("DELETE FROM station_access");

        assertThat(service.aggregateWindow(W)).isZero();
        assertThat(db.count("occupancy_30m")).isZero();
    }

    @Test
    void 구간_시작은_00분_또는_30분이어야_한다() {
        assertThatThrownBy(() -> service.aggregateWindow(W.plusMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 구간_계산_도우미() {
        assertThat(OccupancyAggregationService.floorToWindow(LocalDateTime.of(2026, 9, 27, 10, 29, 59)))
                .isEqualTo(W);
        assertThat(OccupancyAggregationService.floorToWindow(LocalDateTime.of(2026, 9, 27, 10, 30)))
                .isEqualTo(W.plusMinutes(30));
        assertThat(OccupancyAggregationService.isValid(W, List.of(W.minusSeconds(1)))).isTrue();   // 회차가 조금 일찍 시작
        assertThat(OccupancyAggregationService.isValid(W, List.of(W.minusMinutes(10)))).isFalse(); // 10분 전은 제외
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT * FROM occupancy_30m WHERE station_id = ? AND window_start = ?", stationId, W);
    }
}
