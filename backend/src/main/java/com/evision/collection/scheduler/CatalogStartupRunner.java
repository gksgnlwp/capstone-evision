package com.evision.collection.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.evision.collection.service.StationCatalogService;

/**
 * evision.collection.catalog-on-startup=true이면 앱 시작 직후 기본정보 갱신(OP-09)을 1회 실행한다.
 * 처음 배포하거나 로컬에서 확인할 때 새벽 4시를 기다리지 않고 충전소 목록을 채우는 용도다.
 * 수십 분 걸리므로 별도 스레드에서 돌려 기동과 상태 수집을 막지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "evision.collection", name = "catalog-on-startup", havingValue = "true")
public class CatalogStartupRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogStartupRunner.class);

    private final StationCatalogService stationCatalogService;

    public CatalogStartupRunner(StationCatalogService stationCatalogService) {
        this.stationCatalogService = stationCatalogService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartup() {
        log.info("시작 시 기본정보 갱신을 실행합니다 (catalog-on-startup=true).");
        Thread thread = new Thread(stationCatalogService::refreshStationCatalog, "catalog-on-startup");
        thread.start();
    }
}
