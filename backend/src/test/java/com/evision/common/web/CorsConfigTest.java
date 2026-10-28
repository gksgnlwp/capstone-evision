package com.evision.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.evision.TestcontainersConfiguration;

/**
 * 허용 출처의 브라우저만 /api를 GET으로 호출할 수 있다.
 */
@SpringBootTest(properties = "evision.cors.allowed-origins=http://localhost:5173,https://front.example")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CorsConfigTest {

    static final String ALLOWED = "https://front.example";

    @Autowired
    MockMvc mvc;

    @Test
    void 허용_출처의_사전_요청과_GET에_CORS_헤더를_준다() throws Exception {
        mvc.perform(options("/api/routes")
                        .header("Origin", ALLOWED)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED))
                .andExpect(header().string("Access-Control-Allow-Methods", "GET"));

        mvc.perform(get("/api/routes").header("Origin", ALLOWED))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED));
    }

    @Test
    void 허용하지_않은_출처는_거부한다() throws Exception {
        mvc.perform(get("/api/routes").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 조회_외의_메서드는_사전_요청에서_거부한다() throws Exception {
        mvc.perform(options("/api/routes")
                        .header("Origin", ALLOWED)
                        .header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isForbidden());
    }

    @Test
    void Origin이_없는_요청은_그대로_처리한다() throws Exception {
        // 서버 간 호출·curl은 CORS 대상이 아니다
        mvc.perform(get("/api/routes"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
