package com.evision.collection.service;

import java.util.List;

/**
 * @param unknownCount 미등록 충전기로 건너뛴 건수
 * @param invalidCount 형식 오류 건수
 */
public record NormalizationResult(List<NormalizedState> states, int unknownCount, int invalidCount) {
}
