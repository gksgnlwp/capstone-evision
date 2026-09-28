package com.evision.station.query;

import java.time.LocalDateTime;
import java.util.List;

import com.evision.common.code.NormalizedStatus;
import com.evision.station.domain.Charger;

/**
 * 충전소 상세 응답 (명세 6.3).
 */
public record StationDetailResponse(
        long stationId,
        String statId,
        String name,
        String address,
        String addressDetail,
        double lat,
        double lng,
        String orgName,
        String operatorName,
        String operatorCall,
        String useTime,
        Boolean parkingFree,
        Boolean limited,
        String limitDetail,
        LocalDateTime infoUpdatedAt,
        List<AccessPointView> accesses,
        List<ChargerView> chargers) {

    /**
     * @param normalizedStatus 상태 기록이 아직 없으면 UNKNOWN (statusCode, statusUpdatedAt은 null)
     * @param statusCode       원천 stat 코드
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
            LocalDateTime statusUpdatedAt) {

        static ChargerView from(Charger c) {
            NormalizedStatus status = c.getCurrentNormalizedStatus() == null
                    ? NormalizedStatus.UNKNOWN : c.getCurrentNormalizedStatus();
            return new ChargerView(c.getId(), c.getChgerId(), c.getChargerType(), c.getOutputKw(), c.getMethod(),
                    c.isFast(), status, c.getCurrentStatusCode(), c.getCurrentStatusUpdatedAt());
        }
    }
}
