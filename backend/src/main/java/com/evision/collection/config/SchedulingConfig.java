package com.evision.collection.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * evision.collection.enabled=false이면 스케줄러를 켜지 않는다 (로컬·테스트용).
 * 단일 인스턴스 배포를 전제로 한다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "evision.collection", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
