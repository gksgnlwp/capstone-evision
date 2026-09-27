package com.evision.collection.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.evision.common.code.NormalizedStatus;
import com.evision.external.evcharger.dto.StatusItem;

/**
 * OP-11 충전기 상태 정규화.
 *
 * <ul>
 *   <li>statId+chgerId를 charger_id로 대조한다. 미등록 충전기는 unknown으로 건너뛴다.</li>
 *   <li>필수 값 누락, stat 길이 오류, statUpdDt 형식 오류는 invalid로 건너뛴다.</li>
 *   <li>코드표에 없는 stat 값은 UNKNOWN으로 정규화하고, 원천 값은 그대로 보관한다.</li>
 * </ul>
 */
@Component
public class StateNormalizer {

    /** statUpdDt: yyyyMMddHHmmss (KST) */
    static final DateTimeFormatter STAT_UPD_DT = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
            .withResolverStyle(ResolverStyle.STRICT);

    /** OP-11 normalizeStates */
    public NormalizationResult normalize(List<StatusItem> items, Map<String, Long> chargerIds,
            Map<String, NormalizedStatus> statusCodes) {
        List<NormalizedState> states = new ArrayList<>(items.size());
        int unknown = 0;
        int invalid = 0;

        for (StatusItem item : items) {
            String statId = trim(item.statId());
            String chgerId = trim(item.chgerId());
            String stat = trim(item.stat());
            LocalDateTime updatedAt = parseDateTime(trim(item.statUpdDt()));

            if (statId == null || chgerId == null || stat == null || stat.length() != 1 || updatedAt == null) {
                invalid++;
                continue;
            }

            Long chargerId = chargerIds.get(ChargerKeyCache.key(statId, chgerId));
            if (chargerId == null) {
                unknown++;
                continue;
            }

            NormalizedStatus normalized = statusCodes.getOrDefault(stat, NormalizedStatus.UNKNOWN);
            // 충전 시각 항목은 부가 정보라 없거나 형식이 틀려도 레코드를 버리지 않고 null로 둔다.
            states.add(new NormalizedState(chargerId, stat, normalized, updatedAt,
                    parseDateTime(trim(item.lastTsdt())),
                    parseDateTime(trim(item.lastTedt())),
                    parseDateTime(trim(item.nowTsdt()))));
        }
        return new NormalizationResult(states, unknown, invalid);
    }

    private static LocalDateTime parseDateTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, STAT_UPD_DT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
