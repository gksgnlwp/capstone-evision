package com.evision.station.query;

import java.util.List;

/**
 * 노선 목록 (FR-06). 충전소가 매핑된 노선만 노선번호 순으로 준다.
 *
 * @param routes 노선 목록
 */
public record RouteListResponse(List<RouteItem> routes) {

    /**
     * @param directions       이 노선에서 충전소가 매핑된 휴게소 방향 (원천 값, 예: 상행·하행·양방향). 검색의 direction에 그대로 쓴다
     * @param restAreaStations 휴게소에 매핑된 충전소 수
     * @param icStations       IC에 매핑된 충전소 수
     */
    public record RouteItem(long routeId, String routeNo, String routeName, List<String> directions,
            long restAreaStations, long icStations) {
    }
}
