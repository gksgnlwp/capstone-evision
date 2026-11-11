package com.evision.station.reliability;

/**
 * 충전기 현재 상태를 믿을 수 있는지 (9주차 피드백: 오류 충전기는 따로 빼고 믿을 수 있는 것 위주로 보여 주기).
 *
 * <ul>
 * <li>NORMAL: 사용가능·충전중이고 상태가 오래 멈춰 있지 않음</li>
 * <li>SUSPECT: 의심. 충전중이 너무 오래(기본 6시간) 또는 사용가능이 너무 오래(기본 7일) 그대로</li>
 * <li>FAULT: 고장·점검. 운영사가 '사용 불가'로 알려 줌</li>
 * <li>UNKNOWN: 확인 불가. 상태를 알 수 없음</li>
 * </ul>
 */
public enum ChargerReliability {
    NORMAL, SUSPECT, FAULT, UNKNOWN
}
