package jshyun912.backend.exception;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jshyun912.backend.dto.search.SearchRequest;
import jshyun912.backend.service.SearchService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("공통 예외 처리 HTTP 통합 테스트")
class GlobalExceptionHandlerIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SearchService searchService;

    @Test
    @DisplayName("지원하지 않는 메서드는 405와 허용된 메서드 헤더를 반환한다")
    void returnsMethodNotAllowedWithAllowHeader() throws Exception {
        // when / then
        mockMvc.perform(post("/search"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(content().json("""
                        {"status":"FAILED","message":"HTTP_REQUEST_FAILED"}
                        """));
    }

    @Test
    @DisplayName("존재하지 않는 경로는 404를 반환한다")
    void returnsNotFoundForUnknownPath() throws Exception {
        // when / then
        mockMvc.perform(get("/missing-route"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"status":"FAILED","message":"HTTP_REQUEST_FAILED"}
                        """));
    }

    @Test
    @DisplayName("잘못된 검색 조건은 기존 400 상태와 오류 코드를 유지한다")
    void preservesInvalidSearchRequest() throws Exception {
        // when / then
        mockMvc.perform(get("/search").param("dif", "4"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"status":"FAILED","message":"INVALID_SEARCH_REQUEST"}
                        """));
    }

    @Test
    @DisplayName("외부 API 예외는 기존 502 상태와 오류 코드를 유지한다")
    void preservesCodeforcesApiFailure() throws Exception {
        // given
        given(searchService.search(new SearchRequest(List.of("alice"), 4)))
                .willThrow(new ApiException(HttpStatus.BAD_GATEWAY,
                        ErrorMessage.CODEFORCES_API_FAILED, "외부 API 상세 오류"));

        // when / then
        mockMvc.perform(get("/search").param("handle", "alice").param("dif", "4"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(content().json("""
                        {"status":"FAILED","message":"CODEFORCES_API_FAILED"}
                        """));
    }

    @Test
    @DisplayName("예상하지 못한 예외는 내부 상세 내용을 제외하고 500을 반환한다")
    void returnsInternalServerErrorForUnexpectedException() throws Exception {
        // given
        given(searchService.search(new SearchRequest(List.of("alice"), 4)))
                .willThrow(new IllegalStateException("내부 상세 오류"));

        // when / then
        mockMvc.perform(get("/search").param("handle", "alice").param("dif", "4"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(content().json("""
                        {"status":"FAILED","message":"INTERNAL_SERVER_ERROR"}
                        """));
    }
}
