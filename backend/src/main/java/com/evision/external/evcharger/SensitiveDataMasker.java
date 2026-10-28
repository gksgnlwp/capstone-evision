package com.evision.external.evcharger;

import java.util.regex.Pattern;

/**
 * 오류 메시지에 섞여 들어온 인증키를 가린다.
 * RestClient 예외 메시지에는 요청 URL(serviceKey 포함)이 들어갈 수 있다.
 */
public final class SensitiveDataMasker {

    private static final Pattern SERVICE_KEY = Pattern.compile("(?i)(serviceKey=)[^&\"'\\s]*");

    private SensitiveDataMasker() {
    }

    public static String mask(String text) {
        if (text == null) {
            return null;
        }
        return SERVICE_KEY.matcher(text).replaceAll("$1***");
    }
}
