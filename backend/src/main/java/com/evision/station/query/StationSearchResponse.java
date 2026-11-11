package com.evision.station.query;

import java.time.LocalDateTime;
import java.util.List;

import com.evision.station.reliability.StationReliability;

/**
 * 충전소 검색 응답 (명세 6.2). 0건이면 stations가 빈 목록인 200 응답이다.
 *
 * <p>stations는 믿을 수 있는 충전소부터 (reliability: NORMAL → PARTIAL → ALL_ERROR, 같으면 stationId 순).
 * limit은 그 전에 stationId 순으로 자르므로, truncated면 정렬은 받은 목록 안에서만 맞다.
 *
 * @param truncated         limit보다 결과가 많아 잘렸으면 true (지도를 확대하거나 조건을 좁히라는 신호)
 * @param statusCollectedAt 마지막으로 성공한 상태 수집이 끝난 시각. '갱신 지연' 판단은 이 값으로 한다 (없으면 null)
 */
public record StationSearchResponse(int count, int limit, boolean truncated, LocalDateTime statusCollectedAt,
        List<StationSummary> stations) {

    /**
     * 급속 충전기 기준 대수. 다섯 칸을 더하면 fastChargerCount다. 오류 충전기(의심·고장·점검·확인 불가)는
     * availableCount·chargingCount에 넣지 않는다.
     *
     * @param busiId                운영기관 코드. 선호 운영기관 강조용 (FR-36, 운영기관 목록의 busiId와 같음)
     * @param availableCount        정상인 사용가능 대수
     * @param chargingCount         정상인 충전중 대수
     * @param unavailableCount      고장·점검 대수
     * @param unknownCount          확인 불가 대수 (상태를 모름. 0대와 섞지 않는다)
     * @param suspectCount          의심 대수 (충전중·사용가능이 너무 오래 그대로)
     * @param latestStatusUpdatedAt 급속 충전기 상태갱신일시 중 가장 최근 값 (급속 충전기가 없거나 상태 기록이 없으면 null).
     *                              상태가 바뀐 시각이라 수집 지연 판단에는 쓰지 않는다
     * @param reliability           충전소를 믿을 수 있는 정도
     */
    public record StationSummary(
            long stationId,
            String statId,
            String name,
            String busiId,
            double lat,
            double lng,
            List<AccessPointView> accesses,
            int fastChargerCount,
            int availableCount,
            int chargingCount,
            int unavailableCount,
            int unknownCount,
            int suspectCount,
            LocalDateTime latestStatusUpdatedAt,
            StationReliability reliability) {
    }
}
