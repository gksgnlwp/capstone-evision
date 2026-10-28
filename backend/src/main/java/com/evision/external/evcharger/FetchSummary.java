package com.evision.external.evcharger;

/**
 * 페이지 순회 결과 요약.
 *
 * @param fetchedCount  받은 항목 수 합계
 * @param pagesReceived 받은 페이지 수. 0이면 저장할 데이터가 없다
 * @param complete      모든 페이지를 받았는지. false면 errorCode/errorMessage에 중단 사유가 있다
 */
public record FetchSummary(
        Integer totalCount,
        int fetchedCount,
        int apiCallCount,
        int retryCount,
        int pagesReceived,
        boolean complete,
        String errorCode,
        String errorMessage) {
}
