package com.evision.collection.service;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.evision.collection.config.CollectionProperties;
import com.evision.collection.domain.SourceApi;

/**
 * OP-12 상태 이력 대량 적재와 현재 상태 갱신.
 *
 * <p>JPA saveAll을 쓰지 않는다. IDENTITY PK에서는 Hibernate가 JDBC 배치 INSERT를 끄고,
 * ON CONFLICT도 JPA로 표현하기 어렵다. 그래서 JdbcTemplate.batchUpdate를 쓴다.
 *
 * <p>트랜잭션 경계는 회차 1개 = 트랜잭션 1개다. 실패하면 회차 전체가 롤백된다.
 */
@Component
public class StateChangePersister {

    private static final Logger log = LoggerFactory.getLogger(StateChangePersister.class);

    static final String INSERT_HISTORY = """
            INSERT INTO charger_status_history
              (charger_id, status_code, status_updated_at, source_api, run_id, collected_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (charger_id, status_updated_at) DO NOTHING
            """;

    /** 뒤늦게 도착한 과거 상태로 최신값을 되돌리지 않는다. */
    static final String UPDATE_CURRENT = """
            UPDATE charger
            SET current_status_code = ?, current_normalized_status = ?, current_status_updated_at = ?
            WHERE charger_id = ?
              AND (current_status_updated_at IS NULL OR current_status_updated_at < ?)
            """;

    private final JdbcTemplate jdbc;
    private final int batchSize;

    public StateChangePersister(JdbcTemplate jdbc, CollectionProperties properties) {
        this.jdbc = jdbc;
        this.batchSize = properties.persistBatchSize();
    }

    /** OP-12 persistStateChanges */
    @Transactional
    public PersistResult persist(long runId, List<NormalizedState> states, LocalDateTime collectedAt) {
        Map<HistoryKey, NormalizedState> unique = new LinkedHashMap<>();
        Map<HistoryKey, Integer> occurrences = new HashMap<>();
        Set<HistoryKey> conflicted = new HashSet<>();

        for (NormalizedState state : states) {
            HistoryKey key = new HistoryKey(state.chargerId(), state.statusUpdatedAt());
            occurrences.merge(key, 1, Integer::sum);
            NormalizedState previous = unique.putIfAbsent(key, state);
            if (previous != null && !previous.statusCode().equals(state.statusCode())) {
                conflicted.add(key);
            }
        }

        int conflictCount = 0;
        for (HistoryKey key : conflicted) {
            unique.remove(key);
            conflictCount += occurrences.get(key);
            log.warn("동일 키·다른 상태 코드 충돌로 저장하지 않음: chargerId={}, statusUpdatedAt={}",
                    key.chargerId(), key.statusUpdatedAt());
        }

        List<NormalizedState> rows = new ArrayList<>(unique.values());
        int inBatchDuplicates = states.size() - conflictCount - rows.size();
        int inserted = insertHistory(runId, rows, collectedAt);
        int duplicates = inBatchDuplicates + (rows.size() - inserted);

        updateCurrentStatus(rows);
        return new PersistResult(inserted, duplicates, conflictCount);
    }

    private int insertHistory(long runId, List<NormalizedState> rows, LocalDateTime collectedAt) {
        int inserted = 0;
        int[][] results = jdbc.batchUpdate(INSERT_HISTORY, rows, batchSize, (ps, s) -> {
            ps.setLong(1, s.chargerId());
            ps.setString(2, s.statusCode());
            ps.setObject(3, s.statusUpdatedAt());
            ps.setString(4, SourceApi.STATUS.name());
            ps.setLong(5, runId);
            ps.setObject(6, collectedAt);
        });
        for (int[] chunk : results) {
            for (int affected : chunk) {
                if (affected > 0 || affected == Statement.SUCCESS_NO_INFO) {
                    inserted++;
                }
            }
        }
        return inserted;
    }

    private void updateCurrentStatus(List<NormalizedState> rows) {
        Map<Long, NormalizedState> latest = new HashMap<>();
        for (NormalizedState s : rows) {
            latest.merge(s.chargerId(), s,
                    (a, b) -> a.statusUpdatedAt().isBefore(b.statusUpdatedAt()) ? b : a);
        }
        jdbc.batchUpdate(UPDATE_CURRENT, latest.values(), batchSize, (ps, s) -> {
            ps.setString(1, s.statusCode());
            ps.setString(2, s.normalizedStatus().name());
            ps.setObject(3, s.statusUpdatedAt());
            ps.setLong(4, s.chargerId());
            ps.setObject(5, s.statusUpdatedAt());
        });
    }

    private record HistoryKey(long chargerId, LocalDateTime statusUpdatedAt) {
    }
}
