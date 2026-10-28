package com.evision.reference;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * evision.reference.load-on-startup=true이면 앱 시작 시 기준 데이터와 매핑을 적재한다 (명세 4.7).
 * 오류가 있으면 앱 기동을 실패시켜 잘못된 매핑이 서비스에 쓰이지 않게 한다.
 */
@Component
@ConditionalOnProperty(prefix = "evision.reference", name = "load-on-startup", havingValue = "true")
public class ReferenceLoadRunner implements ApplicationRunner {

    private final ReferenceDataLoader loader;

    public ReferenceLoadRunner(ReferenceDataLoader loader) {
        this.loader = loader;
    }

    @Override
    public void run(ApplicationArguments args) {
        loader.loadAll();
    }
}
