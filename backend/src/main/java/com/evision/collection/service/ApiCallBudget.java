package com.evision.collection.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.evision.collection.repository.CollectionRunRepository;
import com.evision.external.evcharger.EvChargerProperties;

/**
 * 일일 API 호출 한도 가드. 당일(KST) collection_run.api_call_count 합계로 잔여 호출 수를 계산한다.
 * 상태 수집(STATUS)과 기본정보 갱신(INFO)을 합산한다.
 */
@Component
public class ApiCallBudget {

    private final CollectionRunRepository runRepository;
    private final EvChargerProperties properties;
    private final Clock clock;

    public ApiCallBudget(CollectionRunRepository runRepository, EvChargerProperties properties, Clock clock) {
        this.runRepository = runRepository;
        this.properties = properties;
        this.clock = clock;
    }

    public int remainingToday() {
        return (int) Math.max(0, properties.dailyCallLimit() - usedToday());
    }

    public long usedToday() {
        return runRepository.sumApiCallCountSince(LocalDate.now(clock).atStartOfDay());
    }

    public int dailyLimit() {
        return properties.dailyCallLimit();
    }
}
