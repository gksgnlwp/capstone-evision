package com.evision.reference;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 작은 기준 데이터 CSV(UTF-8, 첫 줄 헤더)를 읽는다. 큰따옴표로 감싼 값과 "" 이스케이프를 지원한다.
 * 줄바꿈이 들어간 값은 지원하지 않는다 (기준 데이터에는 없다).
 */
public final class CsvReader {

    private CsvReader() {
    }

    /** 헤더 이름 → 값 Map의 목록. 빈 줄은 건너뛴다. */
    public static List<Map<String, String>> read(InputStream in) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return rows;
            }
            List<String> header = parseLine(stripBom(headerLine));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                List<String> values = parseLine(line);
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    String name = header.get(i).trim();
                    if (!name.isEmpty()) {
                        row.put(name, i < values.size() ? values.get(i).trim() : "");
                    }
                }
                rows.add(row);
            }
        }
        return rows;
    }

    static List<String> parseLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values;
    }

    private static String stripBom(String line) {
        return line.startsWith("﻿") ? line.substring(1) : line;
    }
}
