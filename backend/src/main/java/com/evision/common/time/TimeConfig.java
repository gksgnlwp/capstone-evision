package com.evision.common.time;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 전 구간 Asia/Seoul 기준 시각을 사용한다.
 * 현재 시각이 필요한 곳은 {@code LocalDateTime.now(clock)}으로 받아 테스트에서 고정 시각을 주입할 수 있게 한다.
 */
@Configuration
public class TimeConfig {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(KST);
    }
}
