package com.evision.collection.service;

import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.evision.common.code.NormalizedStatus;

/**
 * code_dictionary의 CHARGER_STAT 그룹을 원천 코드 → 정규화 상태 Map으로 읽는다.
 * 행 수가 적어 회차마다 새로 읽는다 (코드표 변경이 바로 반영되도록).
 */
@Component
public class ChargerStatusCodes {

    private final JdbcTemplate jdbc;

    public ChargerStatusCodes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, NormalizedStatus> load() {
        Map<String, NormalizedStatus> map = new HashMap<>();
        jdbc.query("""
                SELECT code, normalized_status FROM code_dictionary
                WHERE code_group = 'CHARGER_STAT' AND normalized_status IS NOT NULL
                """, rs -> {
            map.put(rs.getString(1).trim(), NormalizedStatus.valueOf(rs.getString(2).trim()));
        });
        return Map.copyOf(map);
    }
}
