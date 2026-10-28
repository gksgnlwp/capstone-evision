package com.evision.collection.service;

/**
 * @param insertedCount  새로 저장한 이력 건수
 * @param duplicateCount 이미 있던 이력(같은 충전기·같은 갱신시각)이라 무시한 건수
 * @param conflictCount  같은 배치 안에서 동일 키·다른 상태 코드로 충돌해 저장하지 않은 건수
 */
public record PersistResult(int insertedCount, int duplicateCount, int conflictCount) {
}
