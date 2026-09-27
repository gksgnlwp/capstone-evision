package com.evision.collection.service;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * statId+chgerId → charger_id 메모리 캐시.
 * 충전기 목록은 기본정보 갱신(OP-09) 때만 바뀌므로, 그때 {@link #invalidate()}를 호출한다.
 */
@Component
public class ChargerKeyCache {

    private static final Logger log = LoggerFactory.getLogger(ChargerKeyCache.class);

    private final JdbcTemplate jdbc;
    private volatile Map<String, Long> cache;

    public ChargerKeyCache(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static String key(String statId, String chgerId) {
        return statId.trim() + ":" + chgerId.trim();
    }

    public Map<String, Long> get() {
        Map<String, Long> current = cache;
        if (current == null) {
            synchronized (this) {
                current = cache;
                if (current == null) {
                    current = load();
                    cache = current;
                }
            }
        }
        return current;
    }

    public void invalidate() {
        cache = null;
    }

    private Map<String, Long> load() {
        Map<String, Long> map = new HashMap<>();
        jdbc.query("""
                SELECT s.stat_id, c.chger_id, c.charger_id
                FROM charger c JOIN station s ON s.station_id = c.station_id
                """, rs -> {
            map.put(key(rs.getString(1), rs.getString(2)), rs.getLong(3));
        });
        log.info("충전기 키 캐시 적재: {}건", map.size());
        return Map.copyOf(map);
    }
}
