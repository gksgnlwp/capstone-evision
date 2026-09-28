package com.evision.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.evision.TestcontainersConfiguration;
import com.evision.common.code.NormalizedStatus;
import com.evision.support.TestDb;

/**
 * 배치 크기를 2로 줄여 여러 배치에 걸친 적재와 롤백을 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "evision.collection.persist-batch-size=2")
class StateChangePersisterTest {

    static final LocalDateTime T1 = LocalDateTime.of(2026, 9, 27, 10, 0, 0);
    static final LocalDateTime T2 = LocalDateTime.of(2026, 9, 27, 10, 5, 0);
    static final LocalDateTime COLLECTED_AT = LocalDateTime.of(2026, 9, 27, 10, 6, 0);

    @Autowired
    StateChangePersister persister;
    @Autowired
    JdbcTemplate jdbc;

    TestDb db;
    long runId;
    long chargerA;
    long chargerB;

    @BeforeEach
    void setUp() {
        db = new TestDb(jdbc);
        db.clear();
        long stationId = db.station("ME000001");
        chargerA = db.charger(stationId, "01");
        chargerB = db.charger(stationId, "02");
        runId = db.runningRun();
    }

    @Test
    void 이미_저장된_이력은_중복으로_무시한다() {
        List<NormalizedState> states = List.of(state(chargerA, "2", T1), state(chargerB, "3", T1));

        PersistResult first = persister.persist(runId, states, COLLECTED_AT);
        PersistResult second = persister.persist(runId, states, COLLECTED_AT);

        assertThat(first).isEqualTo(new PersistResult(2, 0, 0));
        assertThat(second).isEqualTo(new PersistResult(0, 2, 0));
        assertThat(db.count("charger_status_history")).isEqualTo(2);
    }

    @Test
    void 같은_배치_안의_같은_이력은_한_번만_저장한다() {
        PersistResult result = persister.persist(runId,
                List.of(state(chargerA, "2", T1), state(chargerA, "2", T1)), COLLECTED_AT);

        assertThat(result).isEqualTo(new PersistResult(1, 1, 0));
    }

    @Test
    void 동일_키_다른_상태_코드는_저장하지_않는다() {
        PersistResult result = persister.persist(runId, List.of(
                state(chargerA, "2", T1),
                state(chargerA, "3", T1),
                state(chargerB, "2", T1)), COLLECTED_AT);

        assertThat(result).isEqualTo(new PersistResult(1, 0, 2));
        assertThat(historyCount(chargerA)).isZero();
        assertThat(current(chargerA).get("current_status_code")).isNull();
        assertThat(historyCount(chargerB)).isEqualTo(1);
    }

    @Test
    void 충전_시각_항목도_이력에_저장한다() {
        LocalDateTime nowStart = LocalDateTime.of(2026, 9, 27, 9, 40, 0);
        persister.persist(runId, List.of(
                new NormalizedState(chargerA, "3", NormalizedStatus.CHARGING, T1, null, null, nowStart)), COLLECTED_AT);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT last_charge_start, now_charge_start FROM charger_status_history WHERE charger_id = ?", chargerA);
        assertThat(row.get("last_charge_start")).isNull();
        assertThat(row.get("now_charge_start").toString()).startsWith("2026-09-27 09:40");
    }

    @Test
    void 현재_상태를_최신값으로_갱신한다() {
        persister.persist(runId, List.of(state(chargerA, "2", T1), state(chargerA, "3", T2)), COLLECTED_AT);

        Map<String, Object> current = current(chargerA);
        assertThat(current.get("current_status_code")).isEqualTo("3");
        assertThat(current.get("current_normalized_status")).isEqualTo("CHARGING");
        assertThat(current.get("current_status_updated_at").toString()).startsWith("2026-09-27 10:05");
    }

    @Test
    void 뒤늦게_도착한_과거_상태가_현재_상태를_되돌리지_않는다() {
        persister.persist(runId, List.of(state(chargerA, "3", T2)), COLLECTED_AT);
        persister.persist(runId, List.of(state(chargerA, "2", T1)), COLLECTED_AT);

        assertThat(current(chargerA).get("current_status_code")).isEqualTo("3");
        assertThat(historyCount(chargerA)).isEqualTo(2);
    }

    @Test
    void 저장_중_실패하면_앞선_배치까지_모두_롤백한다() {
        long missingCharger = 999_999L;

        assertThatThrownBy(() -> persister.persist(runId, List.of(
                state(chargerA, "2", T1),
                state(chargerB, "2", T1),
                state(missingCharger, "2", T1)), COLLECTED_AT))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(db.count("charger_status_history")).isZero();
        assertThat(current(chargerA).get("current_status_code")).isNull();
    }

    private int historyCount(long chargerId) {
        return jdbc.queryForObject("SELECT count(*) FROM charger_status_history WHERE charger_id = ?", Integer.class, chargerId);
    }

    private Map<String, Object> current(long chargerId) {
        return jdbc.queryForMap("""
                SELECT current_status_code, current_normalized_status, current_status_updated_at
                FROM charger WHERE charger_id = ?""", chargerId);
    }

    private static NormalizedState state(long chargerId, String code, LocalDateTime updatedAt) {
        NormalizedStatus normalized = switch (code) {
            case "2" -> NormalizedStatus.AVAILABLE;
            case "3" -> NormalizedStatus.CHARGING;
            default -> NormalizedStatus.UNKNOWN;
        };
        return new NormalizedState(chargerId, code, normalized, updatedAt);
    }
}
