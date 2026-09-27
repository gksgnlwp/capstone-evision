package com.evision.collection.service;

import java.time.LocalDateTime;

import com.evision.common.code.NormalizedStatus;

/**
 * 정규화된 충전기 상태 1건.
 *
 * @param statusCode      원천 stat 값 그대로 (이력에 저장)
 * @param lastChargeStart 마지막 충전시작일시 (없거나 형식 오류면 null)
 * @param lastChargeEnd   마지막 충전종료일시 (없거나 형식 오류면 null)
 * @param nowChargeStart  충전중 시작일시 (없거나 형식 오류면 null)
 */
public record NormalizedState(
        long chargerId,
        String statusCode,
        NormalizedStatus normalizedStatus,
        LocalDateTime statusUpdatedAt,
        LocalDateTime lastChargeStart,
        LocalDateTime lastChargeEnd,
        LocalDateTime nowChargeStart) {

    public NormalizedState(long chargerId, String statusCode, NormalizedStatus normalizedStatus,
            LocalDateTime statusUpdatedAt) {
        this(chargerId, statusCode, normalizedStatus, statusUpdatedAt, null, null, null);
    }
}
