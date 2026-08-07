package jshyun912.backend.controller;

import java.util.List;

import jshyun912.backend.dto.search.SearchRequest;
import jshyun912.backend.dto.search.SearchResponse;
import jshyun912.backend.exception.ApiException;
import jshyun912.backend.exception.ErrorMessage;
import jshyun912.backend.service.SearchService;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@AllArgsConstructor
public class SearchController {
    private SearchService searchService;

    @GetMapping("/search")
    public SearchResponse search(@RequestParam(required = false) List<String> handle,
                                 @RequestParam(required = false) String dif) {
        Integer difficultyMask = parseDifficultyMask(dif);
        validateSearchRequest(handle, difficultyMask);
        return searchService.search(new SearchRequest(handle, difficultyMask));
    }

    private Integer parseDifficultyMask(String difficultyMask) {
        if (difficultyMask == null) {
            return null;
        }
        try {
            return Integer.parseInt(difficultyMask);
        } catch (NumberFormatException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorMessage.INVALID_SEARCH_REQUEST,
                    "dif는 정수여야 합니다.");
        }
    }

    private void validateSearchRequest(List<String> handles, Integer difficultyMask) {
        if (handles == null || handles.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorMessage.INVALID_SEARCH_REQUEST,
                    "핸들을 하나 이상 입력해야 합니다.");
        }
        if (handles.stream().anyMatch(handle -> handle == null || handle.isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorMessage.INVALID_SEARCH_REQUEST,
                    "핸들은 공백일 수 없습니다.");
        }
        if (difficultyMask == null || difficultyMask < 1 || difficultyMask > 127) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorMessage.INVALID_SEARCH_REQUEST,
                    "dif는 1 이상 127 이하여야 합니다.");
        }
    }
}
