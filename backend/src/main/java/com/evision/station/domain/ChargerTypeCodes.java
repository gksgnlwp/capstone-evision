package com.evision.station.domain;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 충전기 타입 코드(원천 chgerType)와 커넥터의 대응. 복합형 충전기는 여러 커넥터를 함께 갖는다.
 * 예: 05(차데모+콤보)는 DC콤보 차량도 쓸 수 있으므로 04(DC콤보)로 찾을 때 함께 나와야 한다.
 *
 * <p>코드표는 한국환경공단 전기자동차 충전소 정보 API 활용가이드 기준이다.
 * 02(AC완속), 08(DC콤보 완속), 11(DC콤보2 버스전용)은 다른 코드와 묶지 않는다.
 */
public final class ChargerTypeCodes {

    private enum Connector { CHADEMO, AC3, COMBO, NACS }

    private static final Map<String, Set<Connector>> CONNECTORS = Map.of(
            "01", Set.of(Connector.CHADEMO),
            "03", Set.of(Connector.CHADEMO, Connector.AC3),
            "04", Set.of(Connector.COMBO),
            "05", Set.of(Connector.CHADEMO, Connector.COMBO),
            "06", Set.of(Connector.CHADEMO, Connector.AC3, Connector.COMBO),
            "07", Set.of(Connector.AC3),
            "09", Set.of(Connector.NACS),
            "10", Set.of(Connector.COMBO, Connector.NACS));

    private ChargerTypeCodes() {
    }

    /**
     * 요청한 타입의 커넥터를 모두 가진 타입 코드들. 요청한 코드 자신을 항상 포함한다.
     * 예: 04 → 04·05·06·10, 05 → 05·06. 코드표에 없는 값은 그 값만 돌려준다.
     */
    public static Set<String> compatibleWith(String chargerType) {
        Set<String> codes = new TreeSet<>();
        codes.add(chargerType);
        Set<Connector> wanted = CONNECTORS.get(chargerType);
        if (wanted != null) {
            CONNECTORS.forEach((code, connectors) -> {
                if (connectors.containsAll(wanted)) {
                    codes.add(code);
                }
            });
        }
        return codes;
    }
}
