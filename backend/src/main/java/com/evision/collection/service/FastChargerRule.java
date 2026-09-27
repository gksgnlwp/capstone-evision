package com.evision.collection.service;

import org.springframework.stereotype.Component;

import com.evision.collection.config.FastChargerRuleProperties;

/**
 * 급속 충전기 판정 (명세 4.5). 출력 기준 또는 타입 코드 기준 중 하나를 만족하면 급속이다.
 */
@Component
public class FastChargerRule {

    private final FastChargerRuleProperties properties;

    public FastChargerRule(FastChargerRuleProperties properties) {
        this.properties = properties;
    }

    public boolean isFast(String chargerType, Integer outputKw) {
        boolean byOutput = properties.minOutputKw() != null && outputKw != null && outputKw >= properties.minOutputKw();
        boolean byType = chargerType != null && properties.fastTypeCodes().contains(chargerType);
        return byOutput || byType;
    }
}
