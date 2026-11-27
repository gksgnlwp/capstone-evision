package com.evision.recommend;

import com.evision.common.error.ApiException;

/**
 * 충전소 추천 조건. 생성 시점에 검증하므로 서비스·리포지토리는 유효한 조건만 받는다.
 * 목적지는 두 값이 모두 있거나 모두 없다. 목적지가 있으면 현재 위치보다 목적지에 가까운 접근지점만 후보로 본다.
 * 노선(routeId)을 주지 않으면 목적지가 꼭 있어야 하고, 노선·방향은 출발지·목적지로 자동 선택한다.
 */
public record RecommendCondition(
        double latitude, double longitude, Long routeId, String direction, double remainingRangeKm,
        Double destinationLatitude, Double destinationLongitude, String chargerType, int limit) {

    public static final int DEFAULT_LIMIT = 5;
    public static final int MAX_LIMIT = 20;
    public static final double MAX_RANGE_KM = 1000;

    public static RecommendCondition of(double latitude, double longitude, Long routeId, String direction,
            double remainingRangeKm, Double destinationLatitude, Double destinationLongitude, String chargerType,
            Integer limit) {
        checkCoordinate(latitude, longitude, "현재 위치");
        if ((destinationLatitude == null) != (destinationLongitude == null)) {
            throw ApiException.invalidQuery("목적지는 destLat, destLng를 모두 지정해야 합니다.");
        }
        if (destinationLatitude != null) {
            checkCoordinate(destinationLatitude, destinationLongitude, "목적지");
        }
        if (routeId == null && destinationLatitude == null) {
            throw ApiException.invalidQuery("routeId 또는 목적지(destLat, destLng)를 지정해야 합니다.");
        }
        if (routeId == null && blankToNull(direction) != null) {
            throw ApiException.invalidQuery("direction은 routeId와 함께 지정해야 합니다. 목적지만 주면 방향은 자동으로 정합니다.");
        }
        if (!(remainingRangeKm > 0 && remainingRangeKm <= MAX_RANGE_KM)) {
            throw ApiException.invalidQuery("remainingRangeKm은 0 초과 " + (int) MAX_RANGE_KM + " 이하여야 합니다.");
        }
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw ApiException.invalidQuery("limit은 1~" + MAX_LIMIT + " 사이여야 합니다.");
        }
        return new RecommendCondition(latitude, longitude, routeId, blankToNull(direction), remainingRangeKm,
                destinationLatitude, destinationLongitude, blankToNull(chargerType), size);
    }

    public boolean autoRoute() {
        return routeId == null;
    }

    /** 자동 선택한 노선·방향으로 바꾼 조건 */
    public RecommendCondition withRoute(long selectedRouteId, String selectedDirection) {
        return new RecommendCondition(latitude, longitude, selectedRouteId, selectedDirection, remainingRangeKm,
                destinationLatitude, destinationLongitude, chargerType, limit);
    }

    public boolean hasDestination() {
        return destinationLatitude != null;
    }

    private static void checkCoordinate(double lat, double lng, String label) {
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw ApiException.invalidQuery(label + " 좌표가 올바르지 않습니다.");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
