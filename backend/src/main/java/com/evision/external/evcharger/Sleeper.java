package com.evision.external.evcharger;

import java.time.Duration;

/**
 * 재시도 대기. 테스트에서 실제로 기다리지 않도록 교체할 수 있게 분리한다.
 */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;
}
