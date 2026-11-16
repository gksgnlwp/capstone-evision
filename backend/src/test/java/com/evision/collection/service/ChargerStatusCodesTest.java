package com.evision.collection.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.evision.TestcontainersConfiguration;
import com.evision.common.code.NormalizedStatus;

/** 마이그레이션으로 들어간 코드표(code_dictionary)를 그대로 읽는지 확인한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChargerStatusCodesTest {

    @Autowired
    ChargerStatusCodes statusCodes;

    @Test
    void 원천이_보내는_상태_코드가_모두_코드표에_있다() {
        assertThat(statusCodes.load())
                .containsEntry("1", NormalizedStatus.UNAVAILABLE)
                .containsEntry("2", NormalizedStatus.AVAILABLE)
                .containsEntry("3", NormalizedStatus.CHARGING)
                .containsEntry("4", NormalizedStatus.UNAVAILABLE)
                .containsEntry("5", NormalizedStatus.UNAVAILABLE)
                .containsEntry("9", NormalizedStatus.UNKNOWN);
    }
}
