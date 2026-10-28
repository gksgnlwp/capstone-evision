package com.evision.reference;

/**
 * 기준 데이터·매핑 파일 오류. 적재 트랜잭션을 롤백한다.
 */
public class ReferenceDataException extends RuntimeException {

    public ReferenceDataException(String message) {
        super(message);
    }
}
