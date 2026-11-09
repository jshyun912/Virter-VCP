package jshyun912.backend.service;

import jshyun912.backend.dto.search.SearchRequest;
import jshyun912.backend.dto.search.SearchResponse;
import jshyun912.backend.dto.codeforces.Contest;
import jshyun912.backend.service.codeforces.CodeforcesService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@AllArgsConstructor
public class SearchService {
    private CodeforcesService codeforcesService;

    public SearchResponse search(SearchRequest request) {
        List<String> handles = request.handles();
        Set<Integer> contests = new HashSet<>();

        for (String handle : handles) {
            Set<Integer> participateContest = codeforcesService.getParticipateContest(handle);
            contests.addAll(participateContest);
        }

        int difficultyMask = request.difficultyMask();
        List<Contest> candidates = codeforcesService.getContestCandidates(difficultyMask);

        candidates.removeIf(contest -> contests.contains(contest.id()));
        Collections.shuffle(candidates);

        return SearchResponse.builder()
                .contestList(candidates.stream().limit(3).toList())
                .build();
    }
}
