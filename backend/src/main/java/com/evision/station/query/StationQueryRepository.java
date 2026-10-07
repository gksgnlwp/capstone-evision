package com.evision.station.query;

import static com.evision.collection.domain.QChargerStatusHistory.chargerStatusHistory;
import static com.evision.reference.domain.QInterchange.interchange;
import static com.evision.reference.domain.QRestArea.restArea;
import static com.evision.station.domain.QCharger.charger;
import static com.evision.station.domain.QStation.station;
import static com.evision.station.domain.QStationAccess.stationAccess;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.evision.collection.domain.ChargerStatusHistory;
import com.evision.common.code.NormalizedStatus;
import com.evision.reference.domain.QRoute;
import com.evision.station.domain.AccessType;
import com.evision.station.domain.Charger;
import com.evision.station.domain.Station;
import com.evision.station.domain.StationAccess;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

/**
 * 조회 API용 Querydsl 쿼리 (명세 6.2~6.4). 조건 메서드는 값이 없으면 null을 반환해 조건을 생략한다.
 */
@Repository
public class StationQueryRepository {

    private static final QRoute restAreaRoute = new QRoute("restAreaRoute");
    private static final QRoute icRoute = new QRoute("icRoute");

    private final JPAQueryFactory query;

    public StationQueryRepository(JPAQueryFactory query) {
        this.query = query;
    }

    /** OP-01 조건에 맞는 서비스 대상(접근지점 매핑 있음) 충전소. station_id 순으로 최대 fetchSize개. */
    public List<Station> searchStations(StationSearchCondition c, int fetchSize) {
        return query.selectFrom(station)
                .where(station.deleted.isFalse(),
                        inArea(c),
                        JPAExpressions.selectOne()
                                .from(stationAccess)
                                .leftJoin(stationAccess.restArea, restArea)
                                .leftJoin(stationAccess.interchange, interchange)
                                .where(stationAccess.station.eq(station),
                                        routeEq(c.routeId()), directionEq(c.direction()),
                                        accessTypeEq(c.accessType()))
                                .exists(),
                        hasChargerType(c.chargerType()))
                .orderBy(station.id.asc())
                .limit(fetchSize)
                .fetch();
    }

    /** 충전소들의 접근지점을 휴게소·IC·노선까지 한 번에 가져온다. 검색 조건이 있으면 맞는 접근지점만. */
    public List<StationAccess> findAccesses(Collection<Long> stationIds, Long routeId, String direction,
            AccessType accessType) {
        return query.selectFrom(stationAccess)
                .leftJoin(stationAccess.restArea, restArea).fetchJoin()
                .leftJoin(restArea.route, restAreaRoute).fetchJoin()
                .leftJoin(stationAccess.interchange, interchange).fetchJoin()
                .leftJoin(interchange.route, icRoute).fetchJoin()
                .where(stationAccess.station.id.in(stationIds),
                        routeEq(routeId), directionEq(direction), accessTypeEq(accessType))
                .orderBy(stationAccess.station.id.asc(), stationAccess.id.asc())
                .fetch();
    }

    public record RouteDirectionRow(Long routeId, String routeNo, String routeName, String direction, Long stations) {
    }

    public record RouteIcRow(Long routeId, String routeNo, String routeName, Long stations) {
    }

    /** FR-06 노선·방향별 휴게소 매핑 충전소 수 (삭제된 충전소 제외) */
    public List<RouteDirectionRow> countRestAreaStationsByRoute() {
        return query.select(Projections.constructor(RouteDirectionRow.class,
                        restAreaRoute.id, restAreaRoute.routeNo, restAreaRoute.routeName, restArea.direction,
                        stationAccess.station.id.countDistinct()))
                .from(stationAccess)
                .join(stationAccess.restArea, restArea)
                .join(restArea.route, restAreaRoute)
                .join(stationAccess.station, station)
                .where(station.deleted.isFalse())
                .groupBy(restAreaRoute.id, restAreaRoute.routeNo, restAreaRoute.routeName, restArea.direction)
                .fetch();
    }

    /** FR-06 노선별 IC 매핑 충전소 수 (삭제된 충전소 제외) */
    public List<RouteIcRow> countIcStationsByRoute() {
        return query.select(Projections.constructor(RouteIcRow.class,
                        icRoute.id, icRoute.routeNo, icRoute.routeName, stationAccess.station.id.countDistinct()))
                .from(stationAccess)
                .join(stationAccess.interchange, interchange)
                .join(interchange.route, icRoute)
                .join(stationAccess.station, station)
                .where(station.deleted.isFalse())
                .groupBy(icRoute.id, icRoute.routeNo, icRoute.routeName)
                .fetch();
    }

    public record StatusCountRow(Long stationId, NormalizedStatus status, Long count, LocalDateTime latestUpdatedAt) {
    }

    /** 급속 충전기 현재 상태별 대수 (GROUP BY 1회). 상태가 아직 없는 충전기는 status가 null인 행으로 나온다. */
    public List<StatusCountRow> countFastChargersByStatus(Collection<Long> stationIds) {
        return query.select(Projections.constructor(StatusCountRow.class,
                        charger.station.id, charger.currentNormalizedStatus, charger.count(),
                        charger.currentStatusUpdatedAt.max()))
                .from(charger)
                .where(charger.station.id.in(stationIds), charger.fast.isTrue(), charger.deleted.isFalse())
                .groupBy(charger.station.id, charger.currentNormalizedStatus)
                .fetch();
    }

    public Optional<Station> findActiveStation(long stationId) {
        return Optional.ofNullable(query.selectFrom(station)
                .where(station.id.eq(stationId), station.deleted.isFalse())
                .fetchOne());
    }

    public List<Charger> findActiveChargers(long stationId) {
        return query.selectFrom(charger)
                .where(charger.station.id.eq(stationId), charger.deleted.isFalse())
                .orderBy(charger.chgerId.asc())
                .fetch();
    }

    public boolean chargerExists(long chargerId) {
        return query.selectOne().from(charger).where(charger.id.eq(chargerId)).fetchFirst() != null;
    }

    /**
     * OP-19 상태 이력 keyset 조회. UNIQUE (charger_id, status_updated_at) 인덱스를 그대로 탄다.
     *
     * @param after 이전 페이지 마지막 status_updated_at (없으면 null)
     */
    public List<ChargerStatusHistory> findHistory(long chargerId, LocalDateTime from, LocalDateTime to,
            LocalDateTime after, int fetchSize) {
        return query.selectFrom(chargerStatusHistory)
                .where(chargerStatusHistory.charger.id.eq(chargerId),
                        chargerStatusHistory.statusUpdatedAt.goe(from),
                        chargerStatusHistory.statusUpdatedAt.lt(to),
                        after == null ? null : chargerStatusHistory.statusUpdatedAt.gt(after))
                .orderBy(chargerStatusHistory.statusUpdatedAt.asc())
                .limit(fetchSize)
                .fetch();
    }

    private BooleanExpression inArea(StationSearchCondition c) {
        if (!c.hasArea()) {
            return null;
        }
        return station.latitude.between(c.minLat(), c.maxLat())
                .and(station.longitude.between(c.minLng(), c.maxLng()));
    }

    private BooleanExpression routeEq(Long routeId) {
        if (routeId == null) {
            return null;
        }
        return restArea.route.id.eq(routeId).or(interchange.route.id.eq(routeId));
    }

    /**
     * 방향은 휴게소에만 있다. IC는 방향 구분이 없으므로 방향을 지정해도 같은 노선의 IC 접근지점은 남긴다 (FR-08).
     * IC를 빼려면 accessType=REST_AREA로 지정한다.
     */
    private BooleanExpression directionEq(String direction) {
        if (direction == null) {
            return null;
        }
        return restArea.direction.eq(direction).or(stationAccess.accessType.eq(AccessType.IC));
    }

    private BooleanExpression accessTypeEq(AccessType accessType) {
        return accessType == null ? null : stationAccess.accessType.eq(accessType);
    }

    private BooleanExpression hasChargerType(String chargerType) {
        if (chargerType == null) {
            return null;
        }
        return JPAExpressions.selectOne()
                .from(charger)
                .where(charger.station.eq(station), charger.deleted.isFalse(), charger.chargerType.eq(chargerType))
                .exists();
    }
}
