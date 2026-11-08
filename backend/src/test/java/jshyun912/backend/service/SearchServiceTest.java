package jshyun912.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;

import jshyun912.backend.dto.codeforces.Contest;
import jshyun912.backend.dto.search.SearchRequest;
import jshyun912.backend.dto.search.SearchResponse;
import jshyun912.backend.exception.ApiException;
import jshyun912.backend.exception.ErrorMessage;
import jshyun912.backend.service.codeforces.CodeforcesService;
import java.util.ArrayList;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("SearchService")
class SearchServiceTest {
    @Mock
    private CodeforcesService codeforcesService;

    private SearchService searchService;

    @BeforeEach
    void setUp() {
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
        willReturn(new ArrayList<>(List.of(participatedContest, div2Contest, div3Contest)))
                .given(codeforcesService).getContestCandidates(6);
        willReturn(Set.of(100)).given(codeforcesService).getParticipateContest("alice");
        willReturn(Set.of()).given(codeforcesService).getParticipateContest("bob");

        // when
        SearchResponse response = searchService.search(new SearchRequest(List.of("alice", "bob"), 6));

        // then
        assertEquals(Set.of(div2Contest, div3Contest), Set.copyOf(response.contestList()));
        then(codeforcesService).should().getParticipateContest("alice");
        then(codeforcesService).should().getParticipateContest("bob");
        then(codeforcesService).should().getContestCandidates(6);
    }

    @Test
    @DisplayName("후보가 네 개 이상이면 세 개만 반환한다")
    void thenReturnsAtMostThreeContests() {
        // given
        List<Contest> candidates = List.of(contest(100), contest(101), contest(102), contest(103));
        willReturn(new ArrayList<>(candidates)).given(codeforcesService).getContestCandidates(4);
        willReturn(Set.of()).given(codeforcesService).getParticipateContest("alice");

        // when
        SearchResponse response = searchService.search(new SearchRequest(List.of("alice"), 4));

        // then
        assertEquals(3, response.contestList().size());
        assertTrue(candidates.containsAll(response.contestList()));
        then(codeforcesService).should().getParticipateContest("alice");
    }

    @Test
    @DisplayName("모든 후보에 참여했으면 빈 목록을 반환한다")
    void thenReturnsEmptyListWhenAllCandidatesWereParticipated() {
        // given
        willReturn(new ArrayList<>(List.of(contest(100)))).given(codeforcesService).getContestCandidates(4);
        willReturn(Set.of(100)).given(codeforcesService).getParticipateContest("alice");

        // when
        SearchResponse response = searchService.search(new SearchRequest(List.of("alice"), 4));

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
