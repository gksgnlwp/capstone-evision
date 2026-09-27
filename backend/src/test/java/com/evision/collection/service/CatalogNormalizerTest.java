package com.evision.collection.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.evision.collection.config.FastChargerRuleProperties;
import com.evision.external.evcharger.EvChargerResponseParser;
import com.evision.external.evcharger.dto.InfoItem;
import com.fasterxml.jackson.databind.ObjectMapper;

class CatalogNormalizerTest {

    CatalogNormalizer normalizer = new CatalogNormalizer(
            new FastChargerRule(new FastChargerRuleProperties(50, List.of())));

    @Test
    void 실제_응답을_정규화한다() throws IOException {
        String body = new ClassPathResource("fixtures/info_real_20260927.json").getContentAsString(StandardCharsets.UTF_8);
        List<InfoItem> items = new EvChargerResponseParser(new ObjectMapper()).parse(body, InfoItem.class).items();

        CatalogNormalizer.Result result = normalizer.normalize(items);

        assertThat(result.invalidCount()).isZero();
        assertThat(result.entries()).hasSize(10);
        CatalogEntry first = result.entries().get(0);
        assertThat(first.statId()).isEqualTo("ME174013");
        assertThat(first.name()).isEqualTo("낙성대동주민센터");
        assertThat(first.latitude()).isEqualTo(37.476296);
        assertThat(first.addressDetail()).isNull();          // addrDetail, location 모두 빈 문자열
        assertThat(first.kindDetail()).isEqualTo("G003");
        assertThat(first.parkingFreeYn()).isEqualTo("Y");
        assertThat(first.outputKw()).isEqualTo(50);
        assertThat(first.fast()).isTrue();                    // 50kW 이상
        assertThat(first.deleted()).isFalse();
        assertThat(first.status().stat()).isEqualTo("2");
    }

    @Test
    void 필수값_누락이나_위경도_변환_실패는_INVALID() {
        CatalogNormalizer.Result result = normalizer.normalize(List.of(
                item("ME000001", "01", "충전소", "abc", "7"),        // 위경도 오류
                item("ME000001", "01", " ", "36.8", "7"),            // 충전소명 없음
                item("ME0000011", "01", "충전소", "36.8", "7"),      // statId 9자리
                item("ME000001", "01", "충전소", "36.8", "7")));

        assertThat(result.invalidCount()).isEqualTo(3);
        assertThat(result.entries()).hasSize(1);
    }

    @Test
    void 긴_이름은_컬럼_크기에_맞춰_자르고_출력이_없으면_급속이_아니다() {
        CatalogEntry entry = normalizer.toEntry(item("ME000001", "01", "가".repeat(150), "36.8", ""));

        assertThat(entry.name()).hasSize(100);
        assertThat(entry.outputKw()).isNull();
        assertThat(entry.fast()).isFalse();
    }

    @Test
    void 타입_코드_기준으로도_급속을_판정한다() {
        CatalogNormalizer byType = new CatalogNormalizer(
                new FastChargerRule(new FastChargerRuleProperties(50, List.of("04"))));

        assertThat(byType.toEntry(item("ME000001", "01", "충전소", "36.8", "7")).fast()).isTrue();
    }

    private static InfoItem item(String statId, String chgerId, String name, String lat, String output) {
        return new InfoItem(name, statId, chgerId, "04", "주소", "", "", lat, "127.2", "", "ME", "기관", "운영기관", "",
                "2", "20260927100000", "", "", "", "", output, "단독", "44", "44131", "A0", "C001", "Y", "", "N", "",
                "N", "", "N", "2020", "1", "F", "");
    }
}
