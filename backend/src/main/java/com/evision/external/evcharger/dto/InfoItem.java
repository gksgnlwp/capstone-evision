package com.evision.external.evcharger.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * getChargerInfo 응답 항목 (충전기 1대당 1행).
 * 필드명은 2026-09-27 실제 응답으로 확정했다 (fixtures/info_real_20260927.json).
 * 값이 없으면 빈 문자열로 온다. 모르는 필드는 무시한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InfoItem(
        String statNm,
        String statId,
        String chgerId,
        String chgerType,
        String addr,
        String addrDetail,
        String location,
        String lat,
        String lng,
        String useTime,
        String busiId,
        String bnm,
        String busiNm,
        String busiCall,
        String stat,
        String statUpdDt,
        String lastTsdt,
        String lastTedt,
        String nowTsdt,
        String powerType,
        String output,
        String method,
        String zcode,
        String zscode,
        String kind,
        String kindDetail,
        String parkingFree,
        String note,
        String limitYn,
        String limitDetail,
        String delYn,
        String delDetail,
        String trafficYn,
        String year,
        String floorNum,
        String floorType,
        String maker) {

    /** 상태 관련 항목만 떼어 상태 정규화(OP-11)에 재사용한다. */
    public StatusItem toStatusItem() {
        return new StatusItem(busiId, statId, chgerId, stat, statUpdDt, lastTsdt, lastTedt, nowTsdt);
    }
}
