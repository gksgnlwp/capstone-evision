package com.evision.station.query;

import java.time.LocalDateTime;
import java.util.List;

import com.evision.common.code.NormalizedStatus;
import com.evision.station.domain.Charger;
import com.evision.station.reliability.ChargerReliability;
import com.evision.station.reliability.StationReliability;

/**
 * 충전소 상세 응답 (명세 6.3).
 *
 * @param busiId            운영기관 코드 (운영기관 목록의 busiId와 같음)
 * @param statusCollectedAt 마지막으로 성공한 상태 수집이 끝난 시각 (없으면 null)
 * @param reliability       급속충전기 기준. 급속이 없으면 null
 * @param chargers          충전기 번호 순. 화면은 reliability가 NORMAL이 아닌 충전기를 따로 묶어 보여 준다
 */
public record StationDetailResponse(
        long stationId,
        String statId,
        String name,
        String address,
        String addressDetail,
        double lat,
        double lng,
        String busiId,
        String orgName,
        String operatorName,
        String operatorCall,
        String useTime,
        Boolean parkingFree,
        Boolean limited,
        String limitDetail,
        LocalDateTime infoUpdatedAt,
        LocalDateTime statusCollectedAt,
        StationReliability reliability,
        List<AccessPointView> accesses,
        List<ChargerView> chargers) {

    /**
     * @param normalizedStatus 상태 기록이 아직 없으면 UNKNOWN (statusCode, statusUpdatedAt은 null)
     * @param statusCode       원천 stat 코드
     * @param reliability      정상·의심·고장·점검·확인 불가
     */
    public record ChargerView(
            long chargerId,
            String chgerId,
            String chargerType,
            Integer outputKw,
            String method,
            boolean fast,
            NormalizedStatus normalizedStatus,
            String statusCode,
            LocalDateTime statusUpdatedAt,
            ChargerReliability reliability) {

        static ChargerView from(Charger c, ChargerReliability reliability) {
            NormalizedStatus status = c.getCurrentNormalizedStatus() == null
                    ? NormalizedStatus.UNKNOWN : c.getCurrentNormalizedStatus();
            return new ChargerView(c.getId(), c.getChgerId(), c.getChargerType(), c.getOutputKw(), c.getMethod(),
                    c.isFast(), status, c.getCurrentStatusCode(), c.getCurrentStatusUpdatedAt(), reliability);
        }
    }
}
