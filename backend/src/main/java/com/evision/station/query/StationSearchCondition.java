package com.evision.station.query;

import com.evision.common.error.ApiException;
import com.evision.station.domain.AccessType;

/**
 * 충전소 검색 조건 (명세 6.2). 생성 시점에 검증하므로 서비스·리포지토리는 유효한 조건만 받는다.
 * 지도 영역은 네 값이 모두 있거나 모두 없다.
 */
public record StationSearchCondition(
        Double minLat, Double minLng, Double maxLat, Double maxLng,
        Long routeId, String direction, AccessType accessType, String chargerType,
        int limit) {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 500;

    public static StationSearchCondition of(Double minLat, Double minLng, Double maxLat, Double maxLng,
            Long routeId, String direction, AccessType accessType, String chargerType, Integer limit) {
        int areaParams = count(minLat, minLng, maxLat, maxLng);
        if (areaParams != 0 && areaParams != 4) {
            throw ApiException.invalidQuery("지도 영역은 minLat, minLng, maxLat, maxLng를 모두 지정해야 합니다.");
        }
        if (areaParams == 0 && routeId == null) {
            throw ApiException.invalidQuery("지도 영역 또는 routeId 중 하나는 필수입니다.");
        }
        if (areaParams == 4 && (minLat > maxLat || minLng > maxLng)) {
            throw ApiException.invalidQuery("지도 영역의 최솟값이 최댓값보다 큽니다.");
        }
        String dir = blankToNull(direction);
        if (dir != null && routeId == null) {
            throw ApiException.invalidQuery("direction은 routeId와 함께 지정해야 합니다.");
        }
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw ApiException.invalidQuery("limit은 1~" + MAX_LIMIT + " 사이여야 합니다.");
        }
        return new StationSearchCondition(minLat, minLng, maxLat, maxLng, routeId, dir, accessType,
                blankToNull(chargerType), size);
    }

    public boolean hasArea() {
        return minLat != null;
    }

    private static int count(Object... values) {
        int n = 0;
        for (Object v : values) {
            if (v != null) {
                n++;
            }
        }
        return n;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
