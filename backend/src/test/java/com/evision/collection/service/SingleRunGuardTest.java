package com.evision.collection.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class SingleRunGuardTest {

    SingleRunGuard guard = new SingleRunGuard();

    @Test
    void 실행_중_재진입하면_건너뛴다() {
        AtomicInteger executed = new AtomicInteger();
        AtomicBoolean reentered = new AtomicBoolean(true);

        boolean ran = guard.runExclusively(() -> {
            executed.incrementAndGet();
            reentered.set(guard.runExclusively(executed::incrementAndGet));
        });

        assertThat(ran).isTrue();
        assertThat(reentered).isFalse();
        assertThat(executed).hasValue(1);
    }

    @Test
    void 작업이_예외로_끝나도_다음_실행은_가능하다() {
        try {
            guard.runExclusively(() -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException ignored) {
        }

        assertThat(guard.runExclusively(() -> { })).isTrue();
    }
}
