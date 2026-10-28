package com.evision.external.evcharger;

/**
 * 외부 API 호출 실패.
 *
 * @see #errorCode() API resultCode 또는 EXTERNAL_API_FAILED 등 내부 오류 코드 (collection_run.error_code)
 * @see #retryable() 네트워크 오류·5xx처럼 다시 시도할 만한 실패인지
 */
public class EvChargerApiException extends RuntimeException {

    public static final String EXTERNAL_API_FAILED = "EXTERNAL_API_FAILED";

    private final String errorCode;
    private final boolean retryable;

    public EvChargerApiException(String errorCode, String message, boolean retryable) {
        super(SensitiveDataMasker.mask(message));
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
