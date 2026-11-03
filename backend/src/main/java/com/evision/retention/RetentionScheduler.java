package com.evision.retention;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 비대상 충전기 이력 정리 스케줄 (매일 새벽). 수집 스케줄러가 꺼져 있거나(evision.collection.enabled=false)
 * evision.retention.enabled=false이면 등록하지 않는다.
 */
@Component
@ConditionalOnExpression("${evision.collection.enabled:true} and ${evision.retention.enabled:true}")
public class RetentionScheduler {

    private final HistoryRetentionService service;

    public RetentionScheduler(HistoryRetentionService service) {
        this.service = service;
    }

    @Scheduled(cron = "${evision.retention.cron:0 40 3 * * *}", zone = "Asia/Seoul")
    public void purge() {
        service.purgeExpired();
    }
}
