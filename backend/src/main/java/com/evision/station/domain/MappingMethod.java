package com.evision.station.domain;

/**
 * 충전소-접근지점 매핑 방식. 휴게소 매핑은 MANUAL만 허용한다 (DB CHECK 제약).
 */
public enum MappingMethod {
    MANUAL,
    AUTO
}
