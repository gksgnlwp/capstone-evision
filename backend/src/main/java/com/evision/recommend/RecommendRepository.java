package com.evision.recommend;

import static com.evision.forecast.domain.QOccupancyForecast.occupancyForecast;
import static com.evision.reference.domain.QInterchange.interchange;
import static com.evision.reference.domain.QRestArea.restArea;
import static com.evision.station.domain.QCharger.charger;
import static com.evision.station.domain.QStation.station;
import static com.evision.station.domain.QStationAccess.stationAccess;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.evision.recommend.AvailabilityPredictor.SlotCount;
import com.evision.reference.domain.QRoute;
import com.evision.station.domain.AccessType;
import com.evision.station.domain.StationAccess;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

/**
 * 추천용 쿼리. 조회는 Querydsl, 과거 통계 집계는 SQL(JdbcTemplate)이다.
 * Querydsl 조건 메서드는 값이 없으면 null을 반환해 조건을 생략한다.
 */
@Repository
public class RecommendRepository {

    /** 양방향 휴게소는 어느 방향으로 달려도 들를 수 있다 (rest_area.direction 원천 값) */
    static final String BOTH_DIRECTIONS = "양방향";

    private static final QRoute restAreaRoute = new QRoute("restAreaRoute");
    private static final QRoute icRoute = new QRoute("icRoute");

    /**
     * 충전소별 도착 슬롯을 기준으로 과거 구간을 (요일, 도착 슬롯과 같은지) 단위로 센다.
     * 커버링 인덱스 (station_id, window_start) INCLUDE (available) 로 Index Only Scan 한다 (V5).
     * 결과는 충전소당 최대 14행 (요일 7 × 슬롯 일치 여부 2).
     */
    static final String HISTORY_COUNTS = """
            WITH target AS (
              SELECT * FROM unnest(CAST(? AS bigint[]), CAST(? AS text[])) AS t(station_id, slot)
            )
            SELECT o.station_id,
                   CAST(extract(isodow FROM o.window_start) AS int)          AS dow,
                   CAST(o.window_start AS time) = CAST(t.slot AS time)       AS same_slot,
                   count(o.available)                                        AS n,
                   count(*) FILTER (WHERE o.available)                       AS hits
            FROM target t
            JOIN occupancy_30m o ON o.station_id = t.station_id
            WHERE o.window_start >= ?
            GROUP BY o.station_id, dow, same_slot
            """;

    private final JPAQueryFactory query;
    private final JdbcTemplate jdbc;

    public RecommendRepository(JPAQueryFactory query, JdbcTemplate jdbc) {
        this.query = query;
        this.jdbc = jdbc;
    }

    /**
     * 노선 위 접근지점과 그 충전소를 휴게소·IC·노선까지 한 번에 가져온다.
     * 삭제되지 않았고 급속충전기(타입 지정 시 그 타입)가 있는 충전소만.
     */
    public List<StationAccess> findCandidateAccesses(long routeId, String direction, String chargerType) {
        return query.selectFrom(stationAccess)
                .join(stationAccess.station, station).fetchJoin()
                .leftJoin(stationAccess.restArea, restArea).fetchJoin()
                .leftJoin(restArea.route, restAreaRoute).fetchJoin()
                .leftJoin(stationAccess.interchange, interchange).fetchJoin()
                .leftJoin(interchange.route, icRoute).fetchJoin()
                .where(station.deleted.isFalse(),
                        restAreaRoute.id.eq(routeId).or(icRoute.id.eq(routeId)),
                        directionMatches(direction),
                        JPAExpressions.selectOne()
                                .from(charger)
                                .where(charger.station.eq(station), charger.deleted.isFalse(), charger.fast.isTrue(),
                                        chargerTypeEq(chargerType))
                                .exists())
                .orderBy(station.id.asc(), stationAccess.id.asc())
                .fetch();
    }

    public record OutputRow(Long stationId, Integer maxOutputKw) {
    }

    /** 급속충전기 최대 출력 (GROUP BY 1회) */
    public List<OutputRow> maxFastOutput(Collection<Long> stationIds) {
        return query.select(Projections.constructor(OutputRow.class, charger.station.id, charger.outputKw.max()))
                .from(charger)
                .where(charger.station.id.in(stationIds), charger.fast.isTrue(), charger.deleted.isFalse())
                .groupBy(charger.station.id)
                .fetch();
    }

    public record HistoryRow(long stationId, SlotCount count) {
    }

    /**
     * [from, ∞) 구간의 과거 가용 통계를 미리 센다.
     *
     * @param slotByStation 충전소별 도착 슬롯 (30분 단위 시각)
     */
    public List<HistoryRow> countHistory(Map<Long, LocalTime> slotByStation, LocalDateTime from) {
        Long[] ids = slotByStation.keySet().toArray(Long[]::new);
        String[] slots = new String[ids.length];
        for (int i = 0; i < ids.length; i++) {
            slots[i] = slotByStation.get(ids[i]).toString();
        }
        return jdbc.query(con -> {
            var ps = con.prepareStatement(HISTORY_COUNTS);
            ps.setArray(1, con.createArrayOf("bigint", ids));
            ps.setArray(2, con.createArrayOf("text", slots));
            ps.setTimestamp(3, Timestamp.valueOf(from));
            return ps;
        }, (rs, i) -> new HistoryRow(rs.getLong("station_id"),
                new SlotCount(DayOfWeek.of(rs.getInt("dow")), rs.getBoolean("same_slot"), rs.getInt("n"),
                        rs.getInt("hits"))));
    }

    public record ForecastRow(Long stationId, LocalDateTime targetWindowStart, BigDecimal pAvailable,
            String modelVersion) {
    }

    /**
     * [fromWindow, toWindow] 구간의 AI 예측 중 generatedAfter 이후에 만든 것만.
     * UNIQUE (station_id, target_window_start) 인덱스를 탄다.
     */
    public List<ForecastRow> findForecasts(Collection<Long> stationIds, LocalDateTime fromWindow,
            LocalDateTime toWindow, LocalDateTime generatedAfter) {
        return query.select(Projections.constructor(ForecastRow.class,
                        occupancyForecast.station.id, occupancyForecast.targetWindowStart,
                        occupancyForecast.pAvailable, occupancyForecast.modelVersion))
                .from(occupancyForecast)
                .where(occupancyForecast.station.id.in(stationIds),
                        occupancyForecast.targetWindowStart.between(fromWindow, toWindow),
                        occupancyForecast.generatedAt.goe(generatedAfter))
                .fetch();
    }

    /** 방향은 휴게소에만 있다. 지정하면 그 방향·양방향 휴게소와 IC가 남는다. */
    private BooleanExpression directionMatches(String direction) {
        if (direction == null) {
            return null;
        }
        return stationAccess.accessType.eq(AccessType.IC)
                .or(restArea.direction.in(direction, BOTH_DIRECTIONS));
    }

    private BooleanExpression chargerTypeEq(String chargerType) {
        return chargerType == null ? null : charger.chargerType.eq(chargerType);
    }
}
