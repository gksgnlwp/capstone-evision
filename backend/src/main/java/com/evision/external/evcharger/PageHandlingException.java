package com.evision.external.evcharger;

/**
 * 받은 페이지를 처리하다 실패했을 때, 그때까지의 호출 요약을 함께 넘긴다.
 * 호출 수가 회차 기록에 남아야 일일 한도 계산이 맞는다.
 */
public class PageHandlingException extends RuntimeException {

    private final FetchSummary summary;

    public PageHandlingException(FetchSummary summary, RuntimeException cause) {
        super(cause.getMessage(), cause);
        this.summary = summary;
    }

    public FetchSummary summary() {
        return summary;
    }

    @Override
    public synchronized RuntimeException getCause() {
        return (RuntimeException) super.getCause();
    }
}
