package jshyun912.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.then;

import jshyun912.backend.exception.ApiException;
import jshyun912.backend.exception.ErrorMessage;
import jshyun912.backend.service.SearchService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("SearchController")
class SearchControllerTest {
    @Mock
    private SearchService searchService;

    private SearchController searchController;

    @BeforeEach
    void setUp() {
        searchController = new SearchController(searchService);
    }

    @Nested
    @DisplayName("search를 호출하면")
    class Search {

        @Test
        @DisplayName("handle이 없으면 잘못된 요청 예외를 던진다")
        void thenThrowsBadRequestWhenHandlesAreMissing() {
            // when
            ApiException exception = assertThrows(ApiException.class, () -> searchController.search(null, "1"));

            // then
            assertInvalidSearchRequest(exception, "핸들을 하나 이상 입력해야 합니다.");
            then(searchService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("공백 handle이 있으면 잘못된 요청 예외를 던진다")
        void thenThrowsBadRequestWhenHandleIsBlank() {
            // when
            ApiException exception = assertThrows(ApiException.class, () -> searchController.search(List.of(" "), "1"));

            // then
            assertInvalidSearchRequest(exception, "핸들은 공백일 수 없습니다.");
            then(searchService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("dif가 0이면 잘못된 요청 예외를 던진다")
        void thenThrowsBadRequestWhenDifficultyMaskIsZero() {
            // when
            ApiException exception = assertThrows(ApiException.class, () -> searchController.search(List.of("alice"), "0"));

            // then
            assertInvalidSearchRequest(exception, "dif는 1 이상 127 이하여야 합니다.");
            then(searchService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("dif가 127을 초과하면 잘못된 요청 예외를 던진다")
        void thenThrowsBadRequestWhenDifficultyMaskExceedsRange() {
            // when
            ApiException exception = assertThrows(ApiException.class, () -> searchController.search(List.of("alice"), "128"));

            // then
            assertInvalidSearchRequest(exception, "dif는 1 이상 127 이하여야 합니다.");
            then(searchService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("dif가 정수가 아니면 잘못된 요청 예외를 던진다")
        void thenThrowsBadRequestWhenDifficultyMaskIsNotAnInteger() {
            // when
            ApiException exception = assertThrows(ApiException.class, () -> searchController.search(List.of("alice"), "abc"));

            // then
            assertInvalidSearchRequest(exception, "dif는 정수여야 합니다.");
            then(searchService).shouldHaveNoInteractions();
        }
    }

    private void assertInvalidSearchRequest(ApiException exception, String detail) {
        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatus());
        assertEquals(ErrorMessage.INVALID_SEARCH_REQUEST, exception.getErrorMessage());
        assertEquals(detail, exception.getDetail());
    }
}
