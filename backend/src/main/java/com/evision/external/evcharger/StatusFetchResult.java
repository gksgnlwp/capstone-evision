package com.evision.external.evcharger;

import java.util.List;

import com.evision.external.evcharger.dto.StatusItem;

/**
 * 상태 전체 조회 결과.
 *
 * @param complete      모든 페이지를 받았는지. false면 errorCode/errorMessage에 중단 사유가 있다
 * @param pagesReceived 받은 페이지 수. 0이면 저장할 데이터가 없다
 */
public record StatusFetchResult(
        List<StatusItem> items,
        Integer totalCount,
        int apiCallCount,
        int retryCount,
        int pagesReceived,
        boolean complete,
        String errorCode,
        String errorMessage) {
}
