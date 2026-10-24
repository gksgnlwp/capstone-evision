package com.evision.station.query;

import java.util.List;

import com.evision.station.query.StationSearchResponse.StationSummary;

/**
 * 즐겨찾기 충전소 요약 (FR-37, OP-108). 요청한 ID 순서대로, 중복은 한 번만 준다.
 * 즐겨찾기 목록은 브라우저에 저장하며 서버는 조회만 한다.
 *
 * @param items 요청한 충전소마다 한 건
 */
public record StationSummaryListResponse(List<Item> items) {

    /** ACTIVE: 운영 중, CLOSED: 운영 종료(삭제됨, 화면은 종료 표시와 삭제 안내), NOT_FOUND: 없는 ID */
    public enum ItemStatus {
        ACTIVE, CLOSED, NOT_FOUND
    }

    /**
     * @param station 검색 결과와 같은 요약 (급속 기준 상태 대수 포함). NOT_FOUND면 null
     */
    public record Item(long stationId, ItemStatus status, StationSummary station) {
    }
}
