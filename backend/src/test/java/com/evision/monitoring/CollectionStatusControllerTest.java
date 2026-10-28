package com.evision.monitoring;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.evision.TestcontainersConfiguration;
import com.evision.support.TestDb;

/**
 * 고정 회차 기록으로 수집 현황을 검증한다. 기간 [10:00, 12:00), 5분 슬롯 24개, 30분 구간 4개.
 *
 * <pre>
 * 10:05 회차는 10:04:59에 시작 (격자보다 조금 이른 시작도 그 슬롯으로 인정)
 * 10:25 FAILED                 → 누락 1슬롯
 * 10:40, 10:45, 10:50 기록 없음 → 연속 누락 3슬롯 (2회 연속 실패 기준 위반)
 * 11:00 PARTIAL                → 성공으로 인정
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CollectionStatusControllerTest {

    static final LocalDateTime D = LocalDateTime.of(2026, 9, 27, 0, 0);

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        TestDb db = new TestDb(jdbc);
        db.clear();
        Set<Integer> missing = Set.of(40, 45, 50);
        for (int m = 0; m < 120; m += 5) {
            if (missing.contains(m)) {
                continue;
            }
            LocalDateTime t = D.plusHours(10).plusMinutes(m);
            String runStatus = m == 25 ? "FAILED" : m == 60 ? "PARTIAL" : "SUCCESS";
            if (m == 5) {
                t = t.minusSeconds(1);
            }
            db.statusRun(t, runStatus);
        }
        jdbc.update("UPDATE collection_run SET fetched_count = 100, inserted_count = 80, duplicate_count = 15, "
                + "unknown_count = 3, invalid_count = 2 WHERE run_status = 'SUCCESS'");
        jdbc.update("""
                INSERT INTO collection_run (run_type, started_at, ended_at, run_status, api_call_count)
                VALUES ('INFO', ?, ?, 'SUCCESS', 53)""", D.plusHours(4), D.plusHours(4).plusMinutes(4));
    }

    @Test
    void 누락_구간과_연속_실패와_가용률을_계산한다() throws Exception {
        mvc.perform(get("/api/admin/collection-status")
                        .param("from", "2026-09-27T10:00:00").param("to", "2026-09-27T12:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.runs.total").value(21))
                .andExpect(jsonPath("$.runs.byStatus.SUCCESS").value(19))
                .andExpect(jsonPath("$.runs.byStatus.PARTIAL").value(1))
                .andExpect(jsonPath("$.runs.byStatus.FAILED").value(1))
                .andExpect(jsonPath("$.counts.fetched").value(1900))
                .andExpect(jsonPath("$.counts.inserted").value(1520))
                .andExpect(jsonPath("$.availability.scheduledSlots").value(24))
                .andExpect(jsonPath("$.availability.successfulSlots").value(20))
                .andExpect(jsonPath("$.availability.slotRate").value(0.8333))
                // 10:30 구간만 불완전 (10:45 시점: 직전 10분 안에 성공 회차 없음)
                .andExpect(jsonPath("$.availability.scheduledWindows").value(4))
                .andExpect(jsonPath("$.availability.completeWindows").value(3))
                .andExpect(jsonPath("$.availability.windowRate").value(0.75))
                .andExpect(jsonPath("$.maxConsecutiveFailures").value(3))
                .andExpect(jsonPath("$.missingRanges.length()").value(2))
                .andExpect(jsonPath("$.missingRanges[0].from").value("2026-09-27T10:25:00"))
                .andExpect(jsonPath("$.missingRanges[0].slots").value(1))
                .andExpect(jsonPath("$.consecutiveFailures.length()").value(1))
                .andExpect(jsonPath("$.consecutiveFailures[0].from").value("2026-09-27T10:40:00"))
                .andExpect(jsonPath("$.consecutiveFailures[0].to").value("2026-09-27T10:50:00"))
                .andExpect(jsonPath("$.consecutiveFailures[0].slots").value(3))
                .andExpect(jsonPath("$.lastSuccess.status").value(startsWith("2026-09-27T11:55")))
                .andExpect(jsonPath("$.lastSuccess.info").value(startsWith("2026-09-27T04:04")))
                .andExpect(jsonPath("$.apiCallsToday.limit").value(950))
                .andExpect(jsonPath("$.disk.totalBytes").isNumber());
    }

    @Test
    void 기록이_없으면_기록_없음으로_정상_응답한다() throws Exception {
        mvc.perform(get("/api/admin/collection-status")
                        .param("from", "2026-01-01T00:00:00").param("to", "2026-01-01T01:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_RECORDS"))
                .andExpect(jsonPath("$.runs.total").value(0));
    }

    @Test
    void 기간_오류는_INVALID_QUERY() throws Exception {
        mvc.perform(get("/api/admin/collection-status")
                        .param("from", "2026-09-27T12:00:00").param("to", "2026-09-27T10:00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUERY"))
                .andExpect(jsonPath("$.timestamp").exists());

        mvc.perform(get("/api/admin/collection-status")
                        .param("from", "2026-08-01T00:00:00").param("to", "2026-09-27T00:00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("조회 기간은 최대 31일입니다."));
    }

    @Test
    void 파라미터_누락이나_형식_오류도_INVALID_QUERY() throws Exception {
        mvc.perform(get("/api/admin/collection-status").param("from", "2026-09-27T10:00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUERY"));

        mvc.perform(get("/api/admin/collection-status").param("from", "어제").param("to", "오늘"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    }
}
