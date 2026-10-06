package com.evision.recommend;

import static com.evision.aggregation.domain.QOccupancy30m.occupancy30m;
import static com.evision.forecast.domain.QOccupancyForecast.occupancyForecast;
import static com.evision.reference.domain.QInterchange.interchange;
import static com.evision.reference.domain.QRestArea.restArea;
import static com.evision.station.domain.QCharger.charger;
import static com.evision.station.domain.QStation.station;
import static com.evision.station.domain.QStationAccess.stationAccess;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Repository;

import com.evision.reference.domain.QRoute;
import com.evision.station.domain.AccessType;
import com.evision.station.domain.StationAccess;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

/**
 * 추천용 Querydsl 쿼리. 조건 메서드는 값이 없으면 null을 반환해 조건을 생략한다.
 */
@Repository
public class RecommendRepository {

    /** 양방향 휴게소는 어느 방향으로 달려도 들를 수 있다 (rest_area.direction 원천 값) */
    static final String BOTH_DIRECTIONS = "양방향";

    private static final QRoute restAreaRoute = new QRoute("restAreaRoute");
    private static final QRoute icRoute = new QRoute("icRoute");

    private final JPAQueryFactory query;

    public RecommendRepository(JPAQueryFactory query) {
        this.query = query;
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

    public record OccupancyRow(Long stationId, LocalDateTime windowStart, Boolean available) {
    }

    /** [from, ∞) 구간의 30분 점유율. UNIQUE (station_id, window_start) 인덱스를 탄다. */
    public List<OccupancyRow> findOccupancy(Collection<Long> stationIds, LocalDateTime from) {
        return query.select(Projections.constructor(OccupancyRow.class,
                        occupancy30m.station.id, occupancy30m.windowStart, occupancy30m.available))
                .from(occupancy30m)
                .where(occupancy30m.station.id.in(stationIds), occupancy30m.windowStart.goe(from))
                .fetch();
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
