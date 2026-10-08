package com.evision.station.query;

import java.time.LocalDateTime;
import java.util.List;

import com.evision.collection.domain.SourceApi;
import com.evision.common.code.NormalizedStatus;

/**
 * 충전기 상태 이력 응답 (명세 6.4). 시각 오름차순.
 *
 * @param nextCursor 다음 페이지 요청 시 cursor로 넘길 값. 더 없으면 null.
 */
public record ChargerHistoryResponse(long chargerId, LocalDateTime from, LocalDateTime to, int size,
        List<Item> items, LocalDateTime nextCursor) {

    /**
     * @param statusUpdatedAt 원천 상태갱신일시 (keyset 기준)
     * @param collectedAt     우리 서버가 수집한 시각
     */
    public record Item(
            String statusCode,
            NormalizedStatus normalizedStatus,
            LocalDateTime statusUpdatedAt,
            LocalDateTime lastChargeStart,
            LocalDateTime lastChargeEnd,
            LocalDateTime nowChargeStart,
            SourceApi sourceApi,
            LocalDateTime collectedAt) {
    }
}
