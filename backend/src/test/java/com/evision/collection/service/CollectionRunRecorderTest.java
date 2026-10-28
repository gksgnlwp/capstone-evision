package com.evision.collection.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.evision.TestcontainersConfiguration;
import com.evision.support.TestDb;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CollectionRunRecorderTest {

    @Autowired
    CollectionRunRecorder recorder;
    @Autowired
    JdbcTemplate jdbc;

    TestDb db;

    @BeforeEach
    void setUp() {
        db = new TestDb(jdbc);
        db.clear();
    }

    @Test
    void 재시작_시_RUNNING으로_남은_회차를_ABORTED로_정리한다() {
        long runId = db.runningRun();

        int aborted = recorder.abortDanglingRuns();

        Map<String, Object> run = jdbc.queryForMap(
                "SELECT run_status, error_code, ended_at FROM collection_run WHERE run_id = ?", runId);
        assertThat(aborted).isEqualTo(1);
        assertThat(run.get("run_status")).isEqualTo("FAILED");
        assertThat(run.get("error_code")).isEqualTo("ABORTED");
        assertThat(run.get("ended_at")).isNotNull();
    }
}
