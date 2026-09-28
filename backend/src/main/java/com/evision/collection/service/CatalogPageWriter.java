package com.evision.collection.service;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.evision.collection.config.CollectionProperties;
import com.evision.collection.domain.SourceApi;
import com.evision.common.code.NormalizedStatus;
import com.evision.external.evcharger.dto.StatusItem;

/**
 * OP-09 기본정보 1페이지를 한 트랜잭션으로 적재한다.
 *
 * <ol>
 *   <li>station upsert (stat_id 기준)</li>
 *   <li>charger upsert ((station, chger_id) 기준)</li>
 *   <li>같은 행의 상태 항목 → 이력 source_api=INFO (중복 무시) + 현재 상태 갱신 (더 최신일 때만)</li>
 * </ol>
 *
 * 전국 약 53만 행을 한 트랜잭션에 넣지 않고 페이지마다 커밋한다. upsert라 다시 실행해도 결과가 같다.
 * 응답에 없는 충전소·충전기는 삭제하거나 del_yn 처리하지 않는다.
 */
@Component
public class CatalogPageWriter {

    static final String UPSERT_STATION = """
            INSERT INTO station (stat_id, name, address, address_detail, latitude, longitude, zcode, zscode,
                                 kind, kind_detail, busi_id, org_name, operator_name, operator_call, use_time,
                                 parking_free_yn, limit_yn, limit_detail, del_yn, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'N', ?)
            ON CONFLICT (stat_id) DO UPDATE SET
              name = EXCLUDED.name, address = EXCLUDED.address, address_detail = EXCLUDED.address_detail,
              latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude,
              zcode = EXCLUDED.zcode, zscode = EXCLUDED.zscode, kind = EXCLUDED.kind, kind_detail = EXCLUDED.kind_detail,
              busi_id = EXCLUDED.busi_id, org_name = EXCLUDED.org_name, operator_name = EXCLUDED.operator_name,
              operator_call = EXCLUDED.operator_call, use_time = EXCLUDED.use_time,
              parking_free_yn = EXCLUDED.parking_free_yn, limit_yn = EXCLUDED.limit_yn,
              limit_detail = EXCLUDED.limit_detail, updated_at = EXCLUDED.updated_at
            """;

    static final String UPSERT_CHARGER = """
            INSERT INTO charger (station_id, chger_id, charger_type, output_kw, method, is_fast, del_yn)
            SELECT station_id, ?, ?, ?, ?, ?, ? FROM station WHERE stat_id = ?
            ON CONFLICT (station_id, chger_id) DO UPDATE SET
              charger_type = EXCLUDED.charger_type, output_kw = EXCLUDED.output_kw, method = EXCLUDED.method,
              is_fast = EXCLUDED.is_fast, del_yn = EXCLUDED.del_yn
            """;

    static final String SELECT_CHARGER_KEYS = """
            SELECT s.stat_id, c.chger_id, c.charger_id
            FROM charger c JOIN station s ON s.station_id = c.station_id
            WHERE s.stat_id = ANY(CAST(? AS text[]))
            """;

    /** 충전기가 하나라도 사용 중이면 N, 모두 삭제면 Y. 이번 회차에 갱신된 충전소만 대상이다. */
    static final String SYNC_STATION_DELETION = """
            UPDATE station s SET del_yn = CASE
              WHEN EXISTS (SELECT 1 FROM charger c WHERE c.station_id = s.station_id AND c.del_yn = 'N') THEN 'N'
              ELSE 'Y' END
            WHERE s.updated_at >= ?
            """;

    private final JdbcTemplate jdbc;
    private final StateNormalizer stateNormalizer;
    private final StateChangePersister stateChangePersister;
    private final int batchSize;

    public CatalogPageWriter(JdbcTemplate jdbc, StateNormalizer stateNormalizer,
            StateChangePersister stateChangePersister, CollectionProperties properties) {
        this.jdbc = jdbc;
        this.stateNormalizer = stateNormalizer;
        this.stateChangePersister = stateChangePersister;
        this.batchSize = properties.persistBatchSize();
    }

    public record PageResult(int stationCount, int chargerCount, PersistResult history) {
    }

    @Transactional
    public PageResult writePage(long runId, List<CatalogEntry> entries, LocalDateTime collectedAt,
            Map<String, NormalizedStatus> statusCodes) {
        // 교착 가능성을 줄이려고 키 순서로 정렬해 갱신한다.
        Map<String, CatalogEntry> stations = new LinkedHashMap<>();
        Map<String, CatalogEntry> chargers = new LinkedHashMap<>();
        entries.stream()
                .sorted(Comparator.comparing(CatalogEntry::statId).thenComparing(CatalogEntry::chgerId))
                .forEach(e -> {
                    stations.putIfAbsent(e.statId(), e);
                    chargers.putIfAbsent(ChargerKeyCache.key(e.statId(), e.chgerId()), e);
                });

        upsertStations(stations.values(), collectedAt);
        upsertChargers(chargers.values());

        Map<String, Long> chargerIds = loadChargerKeys(new ArrayList<>(stations.keySet()));
        List<StatusItem> statusItems = chargers.values().stream().map(CatalogEntry::status).toList();
        NormalizationResult normalized = stateNormalizer.normalize(statusItems, chargerIds, statusCodes);
        PersistResult history = stateChangePersister.persist(runId, normalized.states(), collectedAt, SourceApi.INFO);

        return new PageResult(stations.size(), chargers.size(), history);
    }

    /** 모든 페이지를 받은 뒤에만 호출한다. 일부만 받았으면 충전소의 충전기 목록이 불완전하다. */
    @Transactional
    public int syncStationDeletion(LocalDateTime runStartedAt) {
        return jdbc.update(SYNC_STATION_DELETION, runStartedAt);
    }

    private void upsertStations(Iterable<CatalogEntry> stations, LocalDateTime collectedAt) {
        List<CatalogEntry> rows = new ArrayList<>();
        stations.forEach(rows::add);
        jdbc.batchUpdate(UPSERT_STATION, rows, batchSize, (ps, e) -> {
            ps.setString(1, e.statId());
            ps.setString(2, e.name());
            ps.setString(3, e.address());
            ps.setString(4, e.addressDetail());
            ps.setDouble(5, e.latitude());
            ps.setDouble(6, e.longitude());
            ps.setString(7, e.zcode());
            ps.setString(8, e.zscode());
            ps.setString(9, e.kind());
            ps.setString(10, e.kindDetail());
            ps.setString(11, e.busiId());
            ps.setString(12, e.orgName());
            ps.setString(13, e.operatorName());
            ps.setString(14, e.operatorCall());
            ps.setString(15, e.useTime());
            ps.setString(16, e.parkingFreeYn());
            ps.setString(17, e.limitYn());
            ps.setString(18, e.limitDetail());
            ps.setObject(19, collectedAt);
        });
    }

    private void upsertChargers(Iterable<CatalogEntry> chargers) {
        List<CatalogEntry> rows = new ArrayList<>();
        chargers.forEach(rows::add);
        jdbc.batchUpdate(UPSERT_CHARGER, rows, batchSize, (ps, e) -> {
            ps.setString(1, e.chgerId());
            ps.setString(2, e.chargerType());
            ps.setObject(3, e.outputKw(), java.sql.Types.INTEGER);
            ps.setString(4, e.method());
            ps.setBoolean(5, e.fast());
            ps.setString(6, e.deleted() ? "Y" : "N");
            ps.setString(7, e.statId());
        });
    }

    private Map<String, Long> loadChargerKeys(List<String> statIds) {
        Map<String, Long> map = new HashMap<>();
        jdbc.query(connection -> {
            PreparedStatement ps = connection.prepareStatement(SELECT_CHARGER_KEYS);
            Array array = connection.createArrayOf("text", statIds.toArray());
            ps.setArray(1, array);
            return ps;
        }, rs -> {
            map.put(ChargerKeyCache.key(rs.getString(1), rs.getString(2)), rs.getLong(3));
        });
        return map;
    }
}
