package jshyun912.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import jshyun912.backend.service.codeforces.CodeforcesService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class BackendApplicationTests {
    @MockitoBean
    private CodeforcesService codeforcesService;

    @Test
    @DisplayName("외부 Codeforces API 없이 애플리케이션 컨텍스트를 시작한다")
    void contextLoads() {
    }

}
