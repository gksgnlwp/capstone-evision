package com.evision.station.query;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 충전소 검색 응답 (명세 6.2). 0건이면 stations가 빈 목록인 200 응답이다.
 *
 * @param truncated limit보다 결과가 많아 잘렸으면 true (지도를 확대하거나 조건을 좁히라는 신호)
 */
public record StationSearchResponse(int count, int limit, boolean truncated, List<StationSummary> stations) {

    /**
     * 급속 충전기 기준 상태별 대수. 상태를 아직 모르는 충전기는 unknownCount에 들어가며,
     * 0대(availableCount = 0)와 미확인을 섞지 않는다.
     *
     * @param latestStatusUpdatedAt 급속 충전기 상태갱신일시 중 가장 최근 값 (급속 충전기가 없거나 상태 기록이 없으면 null)
     */
    public record StationSummary(
            long stationId,
            String statId,
            String name,
            double lat,
            double lng,
            List<AccessPointView> accesses,
            int fastChargerCount,
            int availableCount,
            int chargingCount,
            int unavailableCount,
            int unknownCount,
            LocalDateTime latestStatusUpdatedAt) {
    }
}
