package com.evision.aggregation;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 30분 집계 스케줄 (명세 5.2). 직전 구간이 닫히고 2분 뒤에 실행한다.
 * evision.collection.enabled=false이면 등록하지 않는다 (수집 스케줄러와 같은 스위치).
 */
@Component
@ConditionalOnProperty(prefix = "evision.collection", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AggregationScheduler {

    private final OccupancyAggregationService service;
    private final AggregationProperties properties;
    private final Clock clock;

    public AggregationScheduler(OccupancyAggregationService service, AggregationProperties properties, Clock clock) {
        this.service = service;
        this.properties = properties;
        this.clock = clock;
    }

    /** OP-13 */
    @Scheduled(cron = "${evision.aggregation.cron}", zone = "Asia/Seoul")
    public void aggregate() {
        service.aggregateLastClosedWindow();
    }

    /** backfill-days가 있으면 시작 직후 최근 N일을 다시 집계한다 (별도 스레드) */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        if (properties.backfillDays() <= 0) {
            return;
        }
        LocalDateTime to = LocalDateTime.now(clock);
        LocalDateTime from = to.minusDays(properties.backfillDays());
        new Thread(() -> service.backfill(from, to), "aggregation-backfill").start();
    }
}
