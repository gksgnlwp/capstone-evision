package com.evision.collection.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.evision.collection.service.StationCatalogService;
import com.evision.collection.service.StatusCollectionService;

/**
 * 수집 스케줄. evision.collection.enabled=false이면 등록하지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "evision.collection", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CollectionScheduler {

    private final StatusCollectionService statusCollectionService;
    private final StationCatalogService stationCatalogService;

    public CollectionScheduler(StatusCollectionService statusCollectionService,
            StationCatalogService stationCatalogService) {
        this.statusCollectionService = statusCollectionService;
        this.stationCatalogService = stationCatalogService;
    }

    /** OP-09 매일 새벽 충전소 기본정보 갱신 */
    @Scheduled(cron = "${evision.collection.catalog-cron}", zone = "Asia/Seoul")
    public void refreshStationCatalog() {
        stationCatalogService.refreshStationCatalog();
    }

    /** OP-10 5분마다 충전기 상태 수집 (period=10으로 겹쳐 받아 경계 누락 방지) */
    @Scheduled(cron = "${evision.collection.status-cron}", zone = "Asia/Seoul")
    public void collectChargerStates() {
        statusCollectionService.collectChargerStates();
    }
}
