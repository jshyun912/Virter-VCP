package jshyun912.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.spy;

import jshyun912.backend.config.RestClientConfig;
import jshyun912.backend.dto.codeforces.Contest;
import jshyun912.backend.dto.search.SearchRequest;
import jshyun912.backend.dto.search.SearchResponse;
import jshyun912.backend.exception.ApiException;
import jshyun912.backend.exception.ErrorMessage;
import jshyun912.backend.service.codeforces.CodeforcesService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("SearchService")
class SearchServiceTest {
    @Mock
    private RestClientConfig restClientConfig;

    @Mock
    private JsonMapper jsonMapper;

    private CodeforcesService codeforcesService;
    private SearchService searchService;

    @BeforeEach
    void setUp() {
        codeforcesService = spy(new CodeforcesService(restClientConfig, jsonMapper));
        searchService = new SearchService(codeforcesService);
    }

    @Nested
    @DisplayName("search를 호출하면")
    class Search {

    @Test
    @DisplayName("선택한 division 중 누구도 참여하지 않은 대회만 반환한다")
    void thenReturnsOnlySelectedAndUnparticipatedContests() {
        // given
        Contest participatedContest = contest(100);
        Contest div2Contest = contest(101);
        Contest div3Contest = contest(102);
        codeforcesService.div2ContestList.addAll(List.of(participatedContest, div2Contest));
        codeforcesService.div3ContestList.add(div3Contest);
        codeforcesService.div1ContestList.add(contest(103));
        willReturn(Set.of(100)).given(codeforcesService).getParticipateContest("alice");
        willReturn(Set.of()).given(codeforcesService).getParticipateContest("bob");

        // when
        SearchResponse response = searchService.search(new SearchRequest(List.of("alice", "bob"), 6));

        // then
        assertEquals(Set.of(div2Contest, div3Contest), Set.copyOf(response.contestList()));
        then(codeforcesService).should().getParticipateContest("alice");
        then(codeforcesService).should().getParticipateContest("bob");
    }

    @Test
    @DisplayName("후보가 네 개 이상이면 세 개만 반환한다")
    void thenReturnsAtMostThreeContests() {
        // given
        List<Contest> candidates = List.of(contest(100), contest(101), contest(102), contest(103));
        codeforcesService.div2ContestList.addAll(candidates);
        willReturn(Set.of()).given(codeforcesService).getParticipateContest("alice");

        // when
        SearchResponse response = searchService.search(new SearchRequest(List.of("alice"), 4));

        // then
        assertEquals(3, response.contestList().size());
        assertTrue(candidates.containsAll(response.contestList()));
        then(codeforcesService).should().getParticipateContest("alice");
    }

    @Test
    @DisplayName("기타 대회 비트만 선택하면 빈 목록을 반환한다")
    void thenReturnsEmptyListWhenOnlyOtherContestBitIsSelected() {
        // given
        codeforcesService.otherContestList.add(contest(100));
        willReturn(Set.of()).given(codeforcesService).getParticipateContest("alice");

        // when
        SearchResponse response = searchService.search(new SearchRequest(List.of("alice"), 64));

        // then
        assertEquals(List.of(), response.contestList());
        then(codeforcesService).should().getParticipateContest("alice");
    }

    @Test
    @DisplayName("참여 대회 조회가 실패하면 예외를 그대로 전파한다")
    void thenPropagatesApiException() {
        // given
        ApiException expected = new ApiException(HttpStatus.BAD_GATEWAY,
                ErrorMessage.CODEFORCES_API_FAILED, "Codeforces API 요청에 실패했습니다.");
        willThrow(expected).given(codeforcesService).getParticipateContest("alice");

        // when
        ApiException exception = assertThrows(ApiException.class,
                () -> searchService.search(new SearchRequest(List.of("alice"), 4)));

        // then
        assertSame(expected, exception);
        then(codeforcesService).should().getParticipateContest("alice");
    }

    }

    private Contest contest(Integer id) {
        return new Contest(id, "Codeforces Round", "ICPC", "FINISHED", false,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
