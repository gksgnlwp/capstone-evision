package com.evision;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 컨텍스트 기동 = Flyway V1·V2 적용 + Hibernate 스키마 validate 성공.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BackendApplicationTests {

    @Test
    void contextLoads() {
    }
}
