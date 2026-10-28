package com.evision.external.evcharger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.evision.external.evcharger.dto.EvChargerPage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 응답 본문을 파싱한다.
 *
 * <ul>
 *   <li>resultCode가 00이 아니면 실패 (재시도하지 않음)</li>
 *   <li>JSON 요청에도 XML 에러 본문이 올 수 있다 → EXTERNAL_API_FAILED</li>
 *   <li>item이 1건이면 배열이 아니라 객체로 올 수 있다</li>
 * </ul>
 *
 * 응답 봉투: 2026-09-27 실제 호출로 평면 구조({resultCode, totalCount, items.item})임을 확인했다.
 * 공공데이터포털 표준 구조({response.header, response.body})도 방어적으로 해석한다.
 */
@Component
public class EvChargerResponseParser {

    private static final String SUCCESS_CODE = "00";
    private static final Pattern XML_TAG = Pattern.compile("<(returnReasonCode|returnAuthMsg|resultCode|resultMsg)>([^<]*)</\\1>");

    private final ObjectMapper objectMapper;

    public EvChargerResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public <T> EvChargerPage<T> parse(String body, Class<T> itemType) {
        if (body == null || body.isBlank()) {
            throw new EvChargerApiException(EvChargerApiException.EXTERNAL_API_FAILED, "빈 응답 본문", true);
        }
        String trimmed = body.strip();
        if (trimmed.startsWith("<")) {
            throw new EvChargerApiException(EvChargerApiException.EXTERNAL_API_FAILED,
                    "XML 오류 응답: " + summarizeXml(trimmed), false);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(trimmed);
        } catch (JsonProcessingException e) {
            throw new EvChargerApiException(EvChargerApiException.EXTERNAL_API_FAILED,
                    "JSON 파싱 실패: " + e.getOriginalMessage(), false);
        }

        JsonNode header = root.has("response") ? root.path("response").path("header") : root;
        JsonNode bodyNode = root.has("response") ? root.path("response").path("body") : root;

        String resultCode = textOrNull(header.path("resultCode"));
        if (!SUCCESS_CODE.equals(resultCode)) {
            String code = resultCode == null ? EvChargerApiException.EXTERNAL_API_FAILED : resultCode;
            throw new EvChargerApiException(code,
                    "resultCode=" + resultCode + ", resultMsg=" + textOrNull(header.path("resultMsg")), false);
        }

        int totalCount = bodyNode.path("totalCount").asInt(0);
        JsonNode itemNode = bodyNode.path("items").path("item");
        List<T> items = new ArrayList<>();
        try {
            if (itemNode.isArray()) {
                for (JsonNode node : itemNode) {
                    items.add(objectMapper.treeToValue(node, itemType));
                }
            } else if (itemNode.isObject()) {
                items.add(objectMapper.treeToValue(itemNode, itemType));
            }
        } catch (JsonProcessingException e) {
            throw new EvChargerApiException(EvChargerApiException.EXTERNAL_API_FAILED,
                    "항목 변환 실패: " + e.getOriginalMessage(), false);
        }
        return new EvChargerPage<>(totalCount, items);
    }

    private static String summarizeXml(String xml) {
        StringBuilder sb = new StringBuilder();
        Matcher m = XML_TAG.matcher(xml);
        while (m.find()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(m.group(1)).append('=').append(m.group(2).strip());
        }
        return sb.isEmpty() ? "(원인 태그 없음)" : sb.toString();
    }

    private static String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
