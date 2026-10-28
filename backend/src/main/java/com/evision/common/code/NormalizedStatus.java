package com.evision.common.code;

/**
 * 충전기 정규화 상태 (OP-11). 코드표에 없는 원천 stat 값은 UNKNOWN으로 정규화한다.
 */
public enum NormalizedStatus {
    AVAILABLE,
    CHARGING,
    UNAVAILABLE,
    UNKNOWN
}
