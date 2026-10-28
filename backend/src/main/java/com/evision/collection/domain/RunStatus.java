package com.evision.collection.domain;

/**
 * 수집 회차 상태. RUNNING은 중복 실행 통제와 비정상 종료 탐지에 쓴다.
 */
public enum RunStatus {
    RUNNING,
    SUCCESS,
    PARTIAL,
    FAILED
}
