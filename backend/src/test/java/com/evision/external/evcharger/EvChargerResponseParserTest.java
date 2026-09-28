package com.evision.external.evcharger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.evision.external.evcharger.dto.EvChargerPage;
import com.evision.external.evcharger.dto.StatusItem;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * fixtures/status_sample.json은 공식 명세 5개 항목으로 만든 최소 fixture다.
 * fixtures/status_real_20260927.json은 2026-09-27 실제 getChargerStatus 응답(numOfRows=10)이다.
 */
class EvChargerResponseParserTest {

    EvChargerResponseParser parser = new EvChargerResponseParser(new ObjectMapper());

    @Test
    void 실제_상태_응답을_파싱한다() throws IOException {
        String body = new ClassPathResource("fixtures/status_real_20260927.json").getContentAsString(StandardCharsets.UTF_8);

        EvChargerPage<StatusItem> page = parser.parse(body, StatusItem.class);

        assertThat(page.totalCount()).isEqualTo(16616);
        assertThat(page.items()).hasSize(10);
        assertThat(page.items()).allSatisfy(item -> {
            assertThat(item.statId()).hasSize(8);
            assertThat(item.chgerId()).hasSize(2);
            assertThat(item.stat()).hasSize(1);
            assertThat(item.statUpdDt()).hasSize(14);
        });
    }

    @Test
    void 평면_구조_응답을_파싱한다() throws IOException {
        String body = new ClassPathResource("fixtures/status_sample.json").getContentAsString(StandardCharsets.UTF_8);

        EvChargerPage<StatusItem> page = parser.parse(body, StatusItem.class);

        assertThat(page.totalCount()).isEqualTo(3);
        assertThat(page.items()).hasSize(3);
        assertThat(page.items().get(0))
                .isEqualTo(new StatusItem("ME", "ME000001", "01", "2", "20260927101500"));
    }

    @Test
    void 공공데이터포털_표준_구조_응답도_파싱한다() {
        String body = """
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
                 "body":{"totalCount":1,"items":{"item":[
                   {"busiId":"ME","statId":"ME000001","chgerId":"01","stat":"2","statUpdDt":"20260927101500"}]}}}}
                """;

        EvChargerPage<StatusItem> page = parser.parse(body, StatusItem.class);

        assertThat(page.totalCount()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
    }

    @Test
    void item이_1건이면_객체로_와도_파싱한다() {
        String body = """
                {"resultCode":"00","totalCount":1,"items":{"item":
                  {"busiId":"ME","statId":"ME000001","chgerId":"01","stat":2,"statUpdDt":"20260927101500"}}}
                """;

        EvChargerPage<StatusItem> page = parser.parse(body, StatusItem.class);

        assertThat(page.items()).singleElement().extracting(StatusItem::stat).isEqualTo("2");
    }

    @Test
    void 모르는_필드는_무시한다() {
        String body = """
                {"resultCode":"00","totalCount":1,"items":{"item":[
                  {"statId":"ME000001","chgerId":"01","stat":"2","statUpdDt":"20260927101500","newField":"x"}]}}
                """;

        assertThat(parser.parse(body, StatusItem.class).items()).hasSize(1);
    }

    @Test
    void 결과코드가_00이_아니면_재시도하지_않는_실패로_처리한다() {
        String body = """
                {"resultCode":"30","resultMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR"}
                """;

        assertThatThrownBy(() -> parser.parse(body, StatusItem.class))
                .isInstanceOfSatisfying(EvChargerApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo("30");
                    assertThat(e.retryable()).isFalse();
                });
    }

    @Test
    void XML_에러_본문은_EXTERNAL_API_FAILED로_처리한다() {
        String body = """
                <OpenAPI_ServiceResponse><cmmMsgHeader>
                  <errMsg>SERVICE ERROR</errMsg>
                  <returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg>
                  <returnReasonCode>30</returnReasonCode>
                </cmmMsgHeader></OpenAPI_ServiceResponse>
                """;

        assertThatThrownBy(() -> parser.parse(body, StatusItem.class))
                .isInstanceOfSatisfying(EvChargerApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(EvChargerApiException.EXTERNAL_API_FAILED);
                    assertThat(e.getMessage()).contains("returnReasonCode=30");
                    assertThat(e.retryable()).isFalse();
                });
    }

    @Test
    void 오류_메시지의_인증키는_가린다() {
        String masked = SensitiveDataMasker.mask("I/O error on GET http://host/x?serviceKey=abc%2B123&pageNo=1");

        assertThat(masked).doesNotContain("abc").contains("serviceKey=***&pageNo=1");
    }
}
