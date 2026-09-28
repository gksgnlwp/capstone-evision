package com.evision.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.evision.collection.repository.CollectionRunRepository;
import com.evision.common.time.TimeConfig;
import com.evision.external.evcharger.EvChargerProperties;

class ApiCallBudgetTest {

    CollectionRunRepository repository = mock(CollectionRunRepository.class);
    // 2026-09-27 01:30 KST (UTC 기준으로는 전날 16:30)
    Clock clock = Clock.fixed(Instant.parse("2026-09-26T16:30:00Z"), TimeConfig.KST);
    EvChargerProperties properties = new EvChargerProperties(null, null, 9999, 10, null, null, 3, null, null, null, 950);
    ApiCallBudget budget = new ApiCallBudget(repository, properties, clock);

    @Test
    void 한도에서_당일_사용량을_뺀_만큼_남는다() {
        given(repository.sumApiCallCountSince(any())).willReturn(940L);

        assertThat(budget.remainingToday()).isEqualTo(10);
    }

    @Test
    void 한도에_도달하면_0이다() {
        given(repository.sumApiCallCountSince(any())).willReturn(950L);

        assertThat(budget.remainingToday()).isZero();
    }

    @Test
    void 한도를_넘겨도_음수가_아니라_0이다() {
        given(repository.sumApiCallCountSince(any())).willReturn(1200L);

        assertThat(budget.remainingToday()).isZero();
    }

    @Test
    void 당일은_KST_자정부터_계산한다() {
        given(repository.sumApiCallCountSince(any())).willReturn(0L);

        budget.remainingToday();

        verify(repository).sumApiCallCountSince(LocalDateTime.of(2026, 9, 27, 0, 0));
    }
}
