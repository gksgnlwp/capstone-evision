package com.evision.collection.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.evision.external.evcharger.dto.InfoItem;

/**
 * OP-09 기본정보 응답을 station/charger 행으로 정규화한다.
 *
 * <ul>
 *   <li>필수 값(충전소ID, 충전기ID, 충전소명, 주소, 위경도, 지역코드, 기관아이디, 충전기타입)이 없으면 INVALID</li>
 *   <li>위경도 숫자 변환 실패는 INVALID (명세 2.1)</li>
 *   <li>코드 성격 값이 컬럼 크기를 넘으면 필수는 INVALID, 선택은 NULL</li>
 *   <li>이름·주소 같은 서술 값은 컬럼 크기에 맞춰 자른다</li>
 * </ul>
 */
@Component
public class CatalogNormalizer {

    private final FastChargerRule fastChargerRule;

    public CatalogNormalizer(FastChargerRule fastChargerRule) {
        this.fastChargerRule = fastChargerRule;
    }

    public record Result(List<CatalogEntry> entries, int invalidCount) {
    }

    public Result normalize(List<InfoItem> items) {
        List<CatalogEntry> entries = new ArrayList<>(items.size());
        int invalid = 0;
        for (InfoItem item : items) {
            CatalogEntry entry = toEntry(item);
            if (entry == null) {
                invalid++;
            } else {
                entries.add(entry);
            }
        }
        return new Result(entries, invalid);
    }

    CatalogEntry toEntry(InfoItem item) {
        String statId = code(item.statId(), 8);
        String chgerId = code(item.chgerId(), 2);
        String name = text(item.statNm(), 100);
        String address = text(item.addr(), 255);
        Double lat = parseDouble(item.lat());
        Double lng = parseDouble(item.lng());
        String zcode = code(item.zcode(), 2);
        String busiId = code(item.busiId(), 2);
        String chargerType = code(item.chgerType(), 2);

        if (statId == null || chgerId == null || name == null || address == null || lat == null || lng == null
                || zcode == null || busiId == null || chargerType == null) {
            return null;
        }

        String addressDetail = text(item.addrDetail(), 255);
        if (addressDetail == null) {
            addressDetail = text(item.location(), 255);
        }
        Integer outputKw = parseInt(item.output());

        return new CatalogEntry(
                statId, name, address, addressDetail, lat, lng,
                zcode, code(item.zscode(), 5), code(item.kind(), 2), code(item.kindDetail(), 4), busiId,
                text(item.bnm(), 100), text(item.busiNm(), 100), text(item.busiCall(), 20),
                text(item.useTime(), 100), yn(item.parkingFree()), yn(item.limitYn()), text(item.limitDetail(), 255),
                chgerId, chargerType, outputKw, text(item.method(), 20),
                fastChargerRule.isFast(chargerType, outputKw),
                "Y".equalsIgnoreCase(trim(item.delYn())),
                item.toStatusItem());
    }

    /** 코드 값: 공백이면 null, 최대 길이를 넘으면 null (잘라내면 다른 코드가 된다) */
    private static String code(String value, int maxLength) {
        String v = trim(value);
        return v == null || v.length() > maxLength ? null : v;
    }

    /** 서술 값: 공백이면 null, 최대 길이에 맞춰 자른다 */
    private static String text(String value, int maxLength) {
        String v = trim(value);
        if (v == null) {
            return null;
        }
        return v.length() > maxLength ? v.substring(0, maxLength) : v;
    }

    private static String yn(String value) {
        String v = trim(value);
        if (v == null) {
            return null;
        }
        v = v.toUpperCase();
        return "Y".equals(v) || "N".equals(v) ? v : null;
    }

    private static Double parseDouble(String value) {
        String v = trim(value);
        if (v == null) {
            return null;
        }
        try {
            double d = Double.parseDouble(v);
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseInt(String value) {
        String v = trim(value);
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(v).intValue();
        } catch (NumberFormatException e) {
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
