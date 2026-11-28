package com.evision.recommend;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.evision.TestcontainersConfiguration;
import com.evision.common.time.TimeConfig;
import com.evision.support.TestDb;

/**
 * 충전소 도착 시점 예측 API. 현재 시각은 2026-11-25(수) 10:40으로 고정한다.
 *
 * <pre>
 * S1 급속 2대 모두 사용 가능 → 현재 사용률 0 (P_now = 1), 완속 1대는 세지 않는다
 *    11:00 구간 AI 예측 p=0.2 (10분 전 산출) → 30분 뒤 도착(11:10)은 AI_FORECAST
 *        P = e^-1 × 1 + (1 - e^-1) × 0.2 = 0.494
 *    11:30 구간 AI 예측은 2시간 전 산출 → 오래돼서 안 쓰고 과거 통계 (지난주 수 11:30 사용률 0.2)
 * S2 삭제된 충전소 → 404
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StationPredictionControllerTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 10, 40);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW.atZone(TimeConfig.KST).toInstant(), TimeConfig.KST);
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;

    long s1, s2;

    @BeforeEach
    void setUp() {
        TestDb db = new TestDb(jdbc);
        db.clear();
        s1 = db.station("ST000001");
        s2 = db.station("ST000002");
        jdbc.update("UPDATE station SET name = '천안휴게소' WHERE station_id = ?", s1);
        jdbc.update("UPDATE station SET del_yn = 'Y' WHERE station_id = ?", s2);

        charger(s1, "01", true, "AVAILABLE");
        charger(s1, "02", true, "AVAILABLE");
        charger(s1, "03", false, "CHARGING");

        forecast(s1, LocalDateTime.of(2026, 11, 25, 11, 0), 0.2, 0.7, NOW.minusMinutes(10));
        forecast(s1, LocalDateTime.of(2026, 11, 25, 11, 30), 0.1, null, NOW.minusHours(2));
        window(s1, LocalDateTime.of(2026, 11, 18, 11, 30), 0.2);
    }

    @Test
    void 도착_구간에_신선한_AI_예측이_있으면_그것과_현재_상태를_섞는다() throws Exception {
        mvc.perform(get("/api/stations/{id}/prediction", s1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stationId").value(s1))
                .andExpect(jsonPath("$.stationName").value("천안휴게소"))
                .andExpect(jsonPath("$.requestedAt").value("2026-11-25T10:40:00"))
                .andExpect(jsonPath("$.comfortThreshold").value(0.5))
                .andExpect(jsonPath("$.forecastMaxAgeMinutes").value(90))
                .andExpect(jsonPath("$.arrival.arrivalAt").value("2026-11-25T11:10:00"))
                .andExpect(jsonPath("$.arrival.windowStart").value("2026-11-25T11:00:00"))
                .andExpect(jsonPath("$.arrival.minutesAhead").value(30))
                .andExpect(jsonPath("$.arrival.basis").value("AI_FORECAST"))
                .andExpect(jsonPath("$.arrival.probability").value(closeTo(0.494, 0.0005)))
                .andExpect(jsonPath("$.arrival.realtimeWeight").value(closeTo(0.368, 0.0005)))
                .andExpect(jsonPath("$.arrival.forecast.probability").value(0.2))
                .andExpect(jsonPath("$.arrival.forecast.predictedOccupancyRate").value(0.7))
                .andExpect(jsonPath("$.arrival.forecast.modelVersion").value("xgb-v0.7"))
                .andExpect(jsonPath("$.arrival.forecast.generatedAt").value("2026-11-25T10:30:00"))
                .andExpect(jsonPath("$.current.fastChargerCount").value(2))
                .andExpect(jsonPath("$.current.available").value(2))
                .andExpect(jsonPath("$.current.charging").value(0))
                .andExpect(jsonPath("$.current.occupancyRate").value(0.0));
    }

    @Test
    void 오래된_AI_예측은_쓰지_않고_과거_통계로_대신한다() throws Exception {
        mvc.perform(get("/api/stations/{id}/prediction", s1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeline", hasSize(5)))
                .andExpect(jsonPath("$.timeline[0].windowStart").value("2026-11-25T11:00:00"))
                .andExpect(jsonPath("$.timeline[1].arrivalAt").value("2026-11-25T11:40:00"))
                .andExpect(jsonPath("$.timeline[1].windowStart").value("2026-11-25T11:30:00"))
                .andExpect(jsonPath("$.timeline[1].minutesAhead").value(60))
                .andExpect(jsonPath("$.timeline[1].basis").value("SAME_DAY_SLOT"))
                .andExpect(jsonPath("$.timeline[1].basisSampleCount").value(1))
                .andExpect(jsonPath("$.timeline[1].forecast").value(nullValue()))
                .andExpect(jsonPath("$.timeline[4].arrivalAt").value("2026-11-25T13:10:00"))
                .andExpect(jsonPath("$.timeline[4].basis").value("STATION_ALL"));
    }

    @Test
    void 도착_시각이나_분으로_지정할_수_있다() throws Exception {
        mvc.perform(get("/api/stations/{id}/prediction", s1).param("arrivalAt", "2026-11-25T11:10:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.arrival.basis").value("AI_FORECAST"))
                .andExpect(jsonPath("$.arrival.minutesAhead").value(30));
        mvc.perform(get("/api/stations/{id}/prediction", s1).param("minutesLater", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.arrival.windowStart").value("2026-11-25T10:30:00"))
                .andExpect(jsonPath("$.arrival.realtimeWeight").value(1.0))
                .andExpect(jsonPath("$.arrival.probability").value(1.0));
        // 조금 지난 시각은 지금 도착으로 본다
        mvc.perform(get("/api/stations/{id}/prediction", s1).param("arrivalAt", "2026-11-25T10:20:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.arrival.arrivalAt").value("2026-11-25T10:40:00"))
                .andExpect(jsonPath("$.arrival.minutesAhead").value(0));
    }

    @Test
    void 잘못된_시각은_400() throws Exception {
        mvc.perform(get("/api/stations/{id}/prediction", s1).param("minutesLater", "30")
                        .param("arrivalAt", "2026-11-25T11:10:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUERY"));
        mvc.perform(get("/api/stations/{id}/prediction", s1).param("minutesLater", "721"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/stations/{id}/prediction", s1).param("minutesLater", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/stations/{id}/prediction", s1).param("arrivalAt", "2026-11-25T10:00:00"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/stations/{id}/prediction", s1).param("arrivalAt", "2026-11-26T10:41:00"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/stations/{id}/prediction", s1).param("arrivalAt", "내일"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 없거나_삭제된_충전소는_404() throws Exception {
        mvc.perform(get("/api/stations/{id}/prediction", s2))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/stations/{id}/prediction", 999_999))
                .andExpect(status().isNotFound());
    }

    @Test
    void 충전기_상태가_없으면_과거_통계만_쓴다() throws Exception {
        long empty = new TestDb(jdbc).station("ST000003");
        mvc.perform(get("/api/stations/{id}/prediction", empty))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.arrival.basis").value("PRIOR"))
                .andExpect(jsonPath("$.arrival.probability").value(0.7))
                .andExpect(jsonPath("$.arrival.realtimeWeight").value(0.0))
                .andExpect(jsonPath("$.current.fastChargerCount").value(0))
                .andExpect(jsonPath("$.current.occupancyRate").value(nullValue()))
                .andExpect(jsonPath("$.current.lastUpdatedAt").value(nullValue()));
    }

    private void charger(long stationId, String chgerId, boolean fast, String normalized) {
        jdbc.update("""
                INSERT INTO charger (station_id, chger_id, charger_type, output_kw, is_fast, del_yn,
                                     current_normalized_status, current_status_updated_at)
                VALUES (?, ?, '04', 100, ?, 'N', ?, ?)""",
                stationId, chgerId, fast, normalized, NOW.minusMinutes(5));
    }

    private void forecast(long stationId, LocalDateTime window, double p, Double rate, LocalDateTime generatedAt) {
        jdbc.update("""
                INSERT INTO occupancy_forecast (station_id, target_window_start, p_available, predicted_occupancy_rate,
                                                model_version, generated_at)
                VALUES (?, ?, ?::numeric, ?::numeric, 'xgb-v0.7', ?)""",
                stationId, window, p, rate, generatedAt);
    }

    private void window(long stationId, LocalDateTime start, Double rate) {
        jdbc.update("""
                INSERT INTO occupancy_30m (station_id, window_start, fast_charger_count, occupancy_rate, available,
                                           full_minutes, valid_minutes, observed_points, computed_at)
                VALUES (?, ?, 2, ?, true, 0, 30, 6, now())""", stationId, start, rate);
    }
}
