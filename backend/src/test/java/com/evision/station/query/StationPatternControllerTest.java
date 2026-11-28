package com.evision.station.query;

import static org.assertj.core.api.Assertions.assertThat;
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
 * 충전소 혼잡 패턴 API. 현재 시각은 2026-11-25(수) 10:40으로 고정한다.
 * → 집계 기간은 [2026-10-28 10:30, 2026-11-25 10:30) 4주.
 *
 * <pre>
 * S1 월 18:00  11-23 사용률 0.2, 11-16 사용률 0.8 → 평균 0.5, 쾌적 비율 0.5, 표본 2
 *    월 18:00  11-09 사용률 NULL(알 수 없음) → 평균에서 빼고 excludedWindows 1
 *    월 18:00  10-26 → 기간 밖이라 제외
 *    수 10:30  11-25 → 진행 중인 구간이라 제외
 *    일 23:30  11-22 사용률 0.1 → 마지막 칸
 * S2 월 18:00  11-23 사용률 1.0 → 다른 충전소라 제외
 * S3 삭제된 충전소 → 404
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StationPatternControllerTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 10, 40);
    /** 월요일 18:00 칸 = 0일째 × 48 + 36 */
    static final String MON_1800 = "$.slots[36]";
    /** 일요일 23:30 칸 = 6일째 × 48 + 47 */
    static final String SUN_2330 = "$.slots[335]";

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

    long s1, s2, s3;

    @BeforeEach
    void setUp() {
        TestDb db = new TestDb(jdbc);
        db.clear();
        s1 = db.station("ST000001");
        s2 = db.station("ST000002");
        s3 = db.station("ST000003");
        jdbc.update("UPDATE station SET name = '천안휴게소' WHERE station_id = ?", s1);
        jdbc.update("UPDATE station SET del_yn = 'Y' WHERE station_id = ?", s3);

        window(s1, LocalDateTime.of(2026, 11, 23, 18, 0), 0.2);
        window(s1, LocalDateTime.of(2026, 11, 16, 18, 0), 0.8);
        window(s1, LocalDateTime.of(2026, 11, 9, 18, 0), null);
        window(s1, LocalDateTime.of(2026, 10, 26, 18, 0), 0.9);
        window(s1, LocalDateTime.of(2026, 11, 25, 10, 30), 0.9);
        window(s1, LocalDateTime.of(2026, 11, 22, 23, 30), 0.1);
        window(s2, LocalDateTime.of(2026, 11, 23, 18, 0), 1.0);
    }

    @Test
    void 요일_시간대별_평균_사용률과_쾌적_비율() throws Exception {
        mvc.perform(get("/api/stations/{id}/patterns", s1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stationId").value(s1))
                .andExpect(jsonPath("$.stationName").value("천안휴게소"))
                .andExpect(jsonPath("$.weeks").value(4))
                .andExpect(jsonPath("$.from").value("2026-10-28T10:30:00"))
                .andExpect(jsonPath("$.to").value("2026-11-25T10:30:00"))
                .andExpect(jsonPath("$.comfortThreshold").value(0.5))
                .andExpect(jsonPath("$.totalWindows").value(4))
                .andExpect(jsonPath("$.validWindows").value(3))
                .andExpect(jsonPath("$.excludedWindows").value(1))
                .andExpect(jsonPath("$.slots", hasSize(336)))
                .andExpect(jsonPath("$.slots[0].dayOfWeek").value(1))
                .andExpect(jsonPath("$.slots[0].time").value("00:00"))
                .andExpect(jsonPath("$.slots[0].samples").value(0))
                .andExpect(jsonPath("$.slots[0].averageOccupancyRate").value(nullValue()))
                .andExpect(jsonPath("$.slots[0].comfortRate").value(nullValue()))
                .andExpect(jsonPath(MON_1800 + ".dayOfWeek").value(1))
                .andExpect(jsonPath(MON_1800 + ".time").value("18:00"))
                .andExpect(jsonPath(MON_1800 + ".averageOccupancyRate").value(0.5))
                .andExpect(jsonPath(MON_1800 + ".comfortRate").value(0.5))
                .andExpect(jsonPath(MON_1800 + ".samples").value(2))
                .andExpect(jsonPath(SUN_2330 + ".dayOfWeek").value(7))
                .andExpect(jsonPath(SUN_2330 + ".time").value("23:30"))
                .andExpect(jsonPath(SUN_2330 + ".averageOccupancyRate").value(0.1))
                .andExpect(jsonPath(SUN_2330 + ".comfortRate").value(1.0))
                .andExpect(jsonPath(SUN_2330 + ".samples").value(1));
    }

    @Test
    void 주_수를_바꾸면_기간이_바뀐다() throws Exception {
        mvc.perform(get("/api/stations/{id}/patterns", s1).param("weeks", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weeks").value(1))
                .andExpect(jsonPath("$.from").value("2026-11-18T10:30:00"))
                .andExpect(jsonPath("$.totalWindows").value(2))
                .andExpect(jsonPath(MON_1800 + ".averageOccupancyRate").value(0.2))
                .andExpect(jsonPath(MON_1800 + ".comfortRate").value(1.0))
                .andExpect(jsonPath(MON_1800 + ".samples").value(1));
    }

    @Test
    void 집계가_없으면_모든_칸이_빈_값() throws Exception {
        long empty = new TestDb(jdbc).station("ST000004");
        mvc.perform(get("/api/stations/{id}/patterns", empty))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalWindows").value(0))
                .andExpect(jsonPath("$.excludedWindows").value(0))
                .andExpect(jsonPath("$.slots", hasSize(336)))
                .andExpect(jsonPath(MON_1800 + ".samples").value(0));
    }

    @Test
    void 없거나_삭제된_충전소는_404() throws Exception {
        mvc.perform(get("/api/stations/{id}/patterns", s3))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/stations/{id}/patterns", 999_999))
                .andExpect(status().isNotFound());
    }

    @Test
    void 주_수가_범위_밖이면_400() throws Exception {
        mvc.perform(get("/api/stations/{id}/patterns", s1).param("weeks", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUERY"));
        mvc.perform(get("/api/stations/{id}/patterns", s1).param("weeks", "9"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 진행_중인_구간의_시작() {
        assertThat(
                StationPatternService.currentWindowStart(LocalDateTime.of(2026, 11, 25, 10, 29, 59)))
                .isEqualTo(LocalDateTime.of(2026, 11, 25, 10, 0));
        assertThat(
                StationPatternService.currentWindowStart(LocalDateTime.of(2026, 11, 25, 10, 30)))
                .isEqualTo(LocalDateTime.of(2026, 11, 25, 10, 30));
    }

    private void window(long stationId, LocalDateTime start, Double rate) {
        jdbc.update("""
                INSERT INTO occupancy_30m (station_id, window_start, fast_charger_count, occupancy_rate, available,
                                           full_minutes, valid_minutes, observed_points, computed_at)
                VALUES (?, ?, 2, ?, true, 0, 30, 6, now())""", stationId, start, rate);
    }
}
