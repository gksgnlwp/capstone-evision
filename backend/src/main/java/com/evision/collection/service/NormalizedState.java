package com.evision.collection.service;

import java.time.LocalDateTime;

import com.evision.common.code.NormalizedStatus;

/**
 * 정규화된 충전기 상태 1건.
 *
 * @param statusCode 원천 stat 값 그대로 (이력에 저장)
 */
public record NormalizedState(
        long chargerId,
        String statusCode,
        NormalizedStatus normalizedStatus,
        LocalDateTime statusUpdatedAt) {
}
