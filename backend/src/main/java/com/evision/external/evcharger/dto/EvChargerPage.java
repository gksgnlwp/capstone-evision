package com.evision.external.evcharger.dto;

import java.util.List;

/**
 * 파싱된 응답 1페이지.
 */
public record EvChargerPage<T>(int totalCount, List<T> items) {
}
