package com.evision.external.evcharger.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * getChargerStatus 응답 항목. 공식 명세 확인 완료 (응답은 이 5개 항목만 준다).
 *
 * @param busiId    기관아이디 (2)
 * @param statId    충전소ID (8)
 * @param chgerId   충전기ID (2)
 * @param stat      충전기상태 코드 (1)
 * @param statUpdDt 상태갱신일시 yyyyMMddHHmmss (KST)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StatusItem(
        String busiId,
        String statId,
        String chgerId,
        String stat,
        String statUpdDt) {
}
