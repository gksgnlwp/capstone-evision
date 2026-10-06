package com.evision.recommend;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 추천 계산기 빈. 계수는 아직 기본값이며, 백테스트 후 RecommendProperties로 옮긴다.
 */
@Configuration
public class RecommendConfig {

    @Bean
    public AvailabilityPredictor availabilityPredictor() {
        return new AvailabilityPredictor(AvailabilityPredictor.Settings.defaults());
    }

    @Bean
    public RecommendScorer recommendScorer() {
        return new RecommendScorer(RecommendScorer.Settings.defaults());
    }
}
