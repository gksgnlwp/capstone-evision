package com.evision.collection.service;

import com.evision.external.evcharger.dto.StatusItem;

/**
 * 정규화된 기본정보 1행 (충전기 1대). 충전소 정보는 같은 statId의 행마다 반복된다.
 *
 * @param status 같은 행에 들어 있는 상태 정보. 이력에 source_api=INFO로 적재한다
 */
public record CatalogEntry(
        // station
        String statId,
        String name,
        String address,
        String addressDetail,
        double latitude,
        double longitude,
        String zcode,
        String zscode,
        String kind,
        String kindDetail,
        String busiId,
        String orgName,
        String operatorName,
        String operatorCall,
        String useTime,
        String parkingFreeYn,
        String limitYn,
        String limitDetail,
        // charger
        String chgerId,
        String chargerType,
        Integer outputKw,
        String method,
        boolean fast,
        boolean deleted,
        StatusItem status) {
}
