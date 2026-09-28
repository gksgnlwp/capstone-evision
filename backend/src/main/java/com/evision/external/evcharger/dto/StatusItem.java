package com.evision.external.evcharger.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * getChargerStatus 응답 항목.
 *
 * <p>공식 명세는 앞의 5개 항목만 적혀 있지만, 2026-09-27 실제 응답에는
 * lastTsdt, lastTedt, nowTsdt가 함께 왔다 (값이 없으면 빈 문자열).
 *
 * @param busiId    기관아이디 (2)
 * @param statId    충전소ID (8)
 * @param chgerId   충전기ID (2)
 * @param stat      충전기상태 코드 (1)
 * @param statUpdDt 상태갱신일시 yyyyMMddHHmmss (KST)
 * @param lastTsdt  마지막 충전시작일시 (명세 외 항목)
 * @param lastTedt  마지막 충전종료일시 (명세 외 항목)
 * @param nowTsdt   충전중 시작일시 (명세 외 항목)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StatusItem(
        String busiId,
        String statId,
        String chgerId,
        String stat,
        String statUpdDt,
        String lastTsdt,
        String lastTedt,
        String nowTsdt) {

    public StatusItem(String busiId, String statId, String chgerId, String stat, String statUpdDt) {
        this(busiId, statId, chgerId, stat, statUpdDt, null, null, null);
    }
}
