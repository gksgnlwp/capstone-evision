package com.evision.collection.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.evision.collection.service.StatusCollectionService;

/**
 * 수집 스케줄. evision.collection.enabled=false이면 등록하지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "evision.collection", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CollectionScheduler {

    private final StatusCollectionService statusCollectionService;

    public CollectionScheduler(StatusCollectionService statusCollectionService) {
        this.statusCollectionService = statusCollectionService;
    }

    /** OP-10 5분마다 충전기 상태 수집 (period=10으로 겹쳐 받아 경계 누락 방지) */
    @Scheduled(cron = "${evision.collection.status-cron}", zone = "Asia/Seoul")
    public void collectChargerStates() {
        statusCollectionService.collectChargerStates();
    }
}
