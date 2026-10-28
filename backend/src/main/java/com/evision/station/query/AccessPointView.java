package com.evision.station.query;

import java.math.BigDecimal;

import com.evision.reference.domain.Interchange;
import com.evision.reference.domain.RestArea;
import com.evision.reference.domain.Route;
import com.evision.station.domain.AccessType;
import com.evision.station.domain.StationAccess;

/**
 * 충전소 접근지점 (휴게소 또는 IC). 휴게소면 rest* 필드와 direction, IC면 ic* 필드만 값이 있다.
 * 한 충전소가 여러 접근지점(예: 휴게소 + IC)에 매핑될 수 있어 목록으로 내려준다.
 */
public record AccessPointView(
        AccessType accessType,
        Long restAreaId,
        String restAreaName,
        String direction,
        Long icId,
        String icName,
        String facilityType,
        Long routeId,
        String routeNo,
        String routeName,
        BigDecimal detourKm) {

    /** findAccesses처럼 휴게소·IC·노선이 fetch join된 엔티티에서 만든다. */
    static AccessPointView from(StationAccess a) {
        RestArea r = a.getRestArea();
        Interchange ic = a.getInterchange();
        Route route = r != null ? r.getRoute() : ic != null ? ic.getRoute() : null;
        return new AccessPointView(
                a.getAccessType(),
                r == null ? null : r.getId(),
                r == null ? null : r.getName(),
                r == null ? null : r.getDirection(),
                ic == null ? null : ic.getId(),
                ic == null ? null : ic.getName(),
                ic == null ? null : ic.getFacilityType(),
                route == null ? null : route.getId(),
                route == null ? null : route.getRouteNo(),
                route == null ? null : route.getRouteName(),
                a.getDetourKm());
    }
}
