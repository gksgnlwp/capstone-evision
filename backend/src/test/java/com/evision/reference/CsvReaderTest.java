package com.evision.reference;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CsvReaderTest {

    @Test
    void 따옴표와_쉼표가_든_값을_읽는다() throws IOException {
        String csv = "﻿\"코드\",\"이름\",\"노선명\",\n\"0120R00005\",\"동남원IC\",\"광주대구선,무안광주선\",\n\n";

        List<Map<String, String>> rows = CsvReader.read(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("코드", "0120R00005");
            assertThat(row).containsEntry("노선명", "광주대구선,무안광주선");
            assertThat(row).doesNotContainKey("");
        });
    }

    @Test
    void 이스케이프된_큰따옴표를_읽는다() {
        assertThat(CsvReader.parseLine("a,\"say \"\"hi\"\"\",c")).containsExactly("a", "say \"hi\"", "c");
    }

    @Test
    void 노선코드를_노선번호로_바꾼다() {
        assertThat(ReferenceDataLoader.routeNoFromRouteCode("0010")).isEqualTo("1");
        assertThat(ReferenceDataLoader.routeNoFromRouteCode("0207")).isEqualTo("20");   // 반올림하지 않는다
        assertThat(ReferenceDataLoader.routeNoFromRouteCode("0655")).isEqualTo("65");
        assertThat(ReferenceDataLoader.routeNoFromRouteCode("4510")).isEqualTo("451");
        assertThat(ReferenceDataLoader.facilityType("영천JCT")).isEqualTo("JCT");
        assertThat(ReferenceDataLoader.facilityType("옥산하이패스IC")).isEqualTo("IC");
    }
}
