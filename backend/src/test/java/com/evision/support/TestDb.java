package com.evision.support;

import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 통합 테스트용 데이터 준비 도우미. 테스트는 트랜잭션 밖에서 실제 커밋을 하므로 매번 비운다.
 */
public class TestDb {

    private final JdbcTemplate jdbc;

    public TestDb(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void clear() {
        jdbc.execute("""
                TRUNCATE charger_status_history, occupancy_30m, station_access, charger, station, collection_run
                RESTART IDENTITY CASCADE
                """);
    }

    public long station(String statId) {
        return jdbc.queryForObject("""
                INSERT INTO station (stat_id, name, address, latitude, longitude, zcode, busi_id, updated_at)
                VALUES (?, '테스트 충전소', '테스트 주소', 36.8, 127.2, '44', 'ME', now())
                RETURNING station_id""", Long.class, statId);
    }

    public long charger(long stationId, String chgerId) {
        return jdbc.queryForObject("""
                INSERT INTO charger (station_id, chger_id, charger_type, is_fast)
                VALUES (?, ?, '04', true)
                RETURNING charger_id""", Long.class, stationId, chgerId);
    }

    public long runningRun() {
        return jdbc.queryForObject("""
                INSERT INTO collection_run (run_type, started_at, run_status)
                VALUES ('STATUS', now(), 'RUNNING')
                RETURNING run_id""", Long.class);
    }

    public void finishedRunToday(int apiCallCount) {
        jdbc.update("""
                INSERT INTO collection_run (run_type, started_at, ended_at, run_status, api_call_count)
                VALUES ('STATUS', ?, ?, 'SUCCESS', ?)""",
                LocalDateTime.now().withHour(0).withMinute(1), LocalDateTime.now().withHour(0).withMinute(2), apiCallCount);
    }

    public int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
