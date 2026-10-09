package com.evision.station.query;

import java.util.List;

/**
 * 운영기관 목록 (FR-34, OP-107). 서비스 대상 충전소의 운영기관만 충전소 수가 많은 순으로 준다.
 * 선호 운영기관은 브라우저에 저장하며(FR-35), 검색에서 다른 기관을 빼지 않고 busiId로 강조만 한다(FR-36).
 *
 * @param operators 운영기관 목록. stationCount를 모두 더하면 서비스 대상 충전소 수와 같다
 */
public record OperatorListResponse(List<OperatorItem> operators) {

    /**
     * @param busiId       운영기관 코드 (원천 busiId, 예: ME). 검색·상세 응답의 busiId와 같은 값
     * @param name         기관명 (원천 bnm)
     * @param stationCount 이 기관의 서비스 대상 충전소 수
     */
    public record OperatorItem(String busiId, String name, long stationCount) {
    }
}
