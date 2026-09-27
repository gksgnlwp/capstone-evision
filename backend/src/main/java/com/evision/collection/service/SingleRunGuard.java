package com.evision.collection.service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 같은 작업이 겹쳐 실행되지 않게 막는다 (단일 인스턴스 전제).
 */
public class SingleRunGuard {

    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * @return 실행했으면 true, 이미 실행 중이라 건너뛰었으면 false
     */
    public boolean runExclusively(Runnable task) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        try {
            task.run();
            return true;
        } finally {
            running.set(false);
        }
    }
}
