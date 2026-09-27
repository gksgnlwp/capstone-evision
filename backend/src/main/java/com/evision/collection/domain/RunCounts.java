package com.evision.collection.domain;

/**
 * 수집 회차의 건수 집계.
 *
 * @param unknownCount 미등록 충전기로 건너뛴 건수
 * @param invalidCount 형식 오류 건수 (같은 배치 안 동일 키·다른 상태 충돌 포함)
 */
public record RunCounts(
        int apiCallCount,
        Integer totalCount,
        int fetchedCount,
        int insertedCount,
        int duplicateCount,
        int unknownCount,
        int invalidCount,
        int retryCount) {

    public static RunCounts empty() {
        return new RunCounts(0, null, 0, 0, 0, 0, 0, 0);
    }
}
