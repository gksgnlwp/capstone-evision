package com.evision.station.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChargerTypeCodesTest {

    @Test
    void 단일_커넥터_타입은_그_커넥터를_가진_복합형까지_포함한다() {
        assertThat(ChargerTypeCodes.compatibleWith("04")).containsExactly("04", "05", "06", "10");
        assertThat(ChargerTypeCodes.compatibleWith("01")).containsExactly("01", "03", "05", "06");
        assertThat(ChargerTypeCodes.compatibleWith("07")).containsExactly("03", "06", "07");
        assertThat(ChargerTypeCodes.compatibleWith("09")).containsExactly("09", "10");
    }

    @Test
    void 복합형_타입은_커넥터를_모두_가진_타입만_포함한다() {
        assertThat(ChargerTypeCodes.compatibleWith("05")).containsExactly("05", "06");
        assertThat(ChargerTypeCodes.compatibleWith("03")).containsExactly("03", "06");
        assertThat(ChargerTypeCodes.compatibleWith("06")).containsExactly("06");
        assertThat(ChargerTypeCodes.compatibleWith("10")).containsExactly("10");
    }

    @Test
    void 묶지_않는_코드와_코드표에_없는_값은_자기_자신만() {
        assertThat(ChargerTypeCodes.compatibleWith("02")).containsExactly("02");
        assertThat(ChargerTypeCodes.compatibleWith("08")).containsExactly("08");
        assertThat(ChargerTypeCodes.compatibleWith("11")).containsExactly("11");
        assertThat(ChargerTypeCodes.compatibleWith("99")).containsExactly("99");
    }
}
