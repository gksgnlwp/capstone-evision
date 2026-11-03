package com.evision.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 상태 이력 보관 설정. 서비스 대상(station_access 매핑)이 아닌 충전기의 이력만 줄인다.
 *
 * @param enabled   false면 삭제 배치를 돌리지 않는다
 * @param cron      삭제 배치 시각. 집계(매시 2·32분)·기본정보 갱신(04:00)과 겹치지 않게 둔다
 * @param keepDays  비대상 충전기 이력을 남기는 기간. 매주 한 주치를 서버 밖으로 백업하므로, 한 주를 놓쳐도 되게 2주로 둔다
 * @param batchSize 한 번에 지울 history_id 범위. 한 트랜잭션이 너무 커지지 않게(WAL·잠금) 나눠 지운다
 */
@ConfigurationProperties(prefix = "evision.retention")
public record RetentionProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("0 40 3 * * *") String cron,
        @DefaultValue("14") int keepDays,
        @DefaultValue("50000") int batchSize) {
}
