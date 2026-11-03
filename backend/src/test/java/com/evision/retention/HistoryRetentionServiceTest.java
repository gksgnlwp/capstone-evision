package com.evision.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.evision.TestcontainersConfiguration;
import com.evision.support.TestDb;

/**
 * 비대상 충전기 이력 정리. 기준 시각 10/20 00:00, 범위 크기 2 (여러 범위로 나눠 지우는지 확인).
 *
 * <pre>
 * 대상 충전기 T (휴게소 매핑)   10/01 10/10 10/25  → 모두 남김
 * 비대상 충전기 N               10/01 10/10 10/25  → 10/01·10/10 삭제
 * 비대상 충전기 M (오래 안 바뀜) 10/01 10/05        → 10/01 삭제, 최신 10/05는 남김
 * </pre>
 */
@SpringBootTest(properties = "evision.retention.batch-size=2")
@Import(TestcontainersConfiguration.class)
class HistoryRetentionServiceTest {

    static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 10, 20, 0, 0);

    @Autowired
    HistoryRetentionService service;
    @Autowired
    JdbcTemplate jdbc;

    TestDb db;
    long target, nonTarget, stale;

    @BeforeEach
    void setUp() {
        db = new TestDb(jdbc);
        db.clear();
        long targetStation = db.station("ME000001");
        db.mapToRestArea(targetStation);
        target = db.charger(targetStation, "01");
        long otherStation = db.station("ME000002");
        nonTarget = db.charger(otherStation, "01");
        stale = db.charger(otherStation, "02");

        long run = db.statusRun(LocalDateTime.of(2026, 10, 1, 0, 0), "SUCCESS");
        // 수집 순서(= history_id 순서)대로 넣는다
        history(run, at(1), target, nonTarget, stale);
        history(run, at(5), stale);
        history(run, at(10), target, nonTarget);
        history(run, at(25), target, nonTarget);
    }

    @Test
    void 비대상_충전기의_오래된_이력만_지우고_충전기별_최신_이력은_남긴다() {
        HistoryRetentionService.Result result = service.purgeBefore(CUTOFF);

        assertThat(result.deleted()).isEqualTo(3);
        assertThat(result.batches()).isGreaterThan(1);
        assertThat(days(target)).containsExactly(1, 10, 25);
        assertThat(days(nonTarget)).containsExactly(25);
        assertThat(days(stale)).containsExactly(5);
    }

    @Test
    void 다시_실행해도_더_지우지_않는다() {
        service.purgeBefore(CUTOFF);

        assertThat(service.purgeBefore(CUTOFF).deleted()).isZero();
    }

    @Test
    void 매핑이_하나도_없으면_아무것도_지우지_않는다() {
        jdbc.execute("DELETE FROM station_access");

        assertThat(service.purgeBefore(CUTOFF).deleted()).isZero();
        assertThat(db.count("charger_status_history")).isEqualTo(8);
    }

    @Test
    void 기준_시각_이후에_수집된_첫_이력을_기본키로_찾는다() {
        long min = jdbc.queryForObject("SELECT min(history_id) FROM charger_status_history", Long.class);
        long firstAfter = jdbc.queryForObject(
                "SELECT min(history_id) FROM charger_status_history WHERE collected_at >= ?", Long.class, CUTOFF);
        long max = jdbc.queryForObject("SELECT max(history_id) FROM charger_status_history", Long.class);

        assertThat(service.firstIdCollectedAtOrAfter(min, CUTOFF)).isEqualTo(firstAfter);
        assertThat(service.firstIdCollectedAtOrAfter(min, at(26))).isEqualTo(max + 1);
        assertThat(service.firstIdCollectedAtOrAfter(min, at(1))).isEqualTo(min);
    }

    private static LocalDateTime at(int day) {
        return LocalDateTime.of(2026, 10, day, 12, 0);
    }

    private void history(long run, LocalDateTime at, long... chargerIds) {
        for (long chargerId : chargerIds) {
            db.history(chargerId, "3", at, run);
        }
    }

    private List<Integer> days(long chargerId) {
        return jdbc.queryForList("""
                SELECT EXTRACT(DAY FROM status_updated_at)::int FROM charger_status_history
                WHERE charger_id = ? ORDER BY status_updated_at""", Integer.class, chargerId);
    }
}
