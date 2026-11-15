package com.evision.station.reliability;

/**
 * 충전소의 급속충전기를 믿을 수 있는 정도. 검색 결과는 이 순서(NORMAL → PARTIAL → ALL_ERROR)로 정렬한다.
 *
 * <ul>
 * <li>NORMAL: 급속충전기가 모두 정상</li>
 * <li>PARTIAL: 정상과 오류(의심·고장·점검·확인 불가)가 섞여 있음</li>
 * <li>ALL_ERROR: 정상인 급속충전기가 한 대도 없음. 추천에서 뺀다</li>
 * </ul>
 */
public enum StationReliability {
    NORMAL, PARTIAL, ALL_ERROR;

    public static StationReliability of(int normalCount, int totalCount) {
        if (normalCount == 0) {
            return ALL_ERROR;
        }
        return normalCount == totalCount ? NORMAL : PARTIAL;
    }
}
