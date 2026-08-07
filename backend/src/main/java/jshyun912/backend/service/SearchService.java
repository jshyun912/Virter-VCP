package jshyun912.backend.service;

import jshyun912.backend.dto.search.SearchRequest;
import jshyun912.backend.dto.search.SearchResponse;
import jshyun912.backend.dto.codeforces.Contest;
import jshyun912.backend.service.codeforces.CodeforcesService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
        List<Contest> candidates = new ArrayList<>();

        if ((difficultyMask & 1) != 0) {
            candidates.addAll(codeforcesService.div4ContestList);
        }
        if ((difficultyMask & 2) != 0) {
            candidates.addAll(codeforcesService.div3ContestList);
        }
        if ((difficultyMask & 4) != 0) {
            candidates.addAll(codeforcesService.div2ContestList);
        }
        if ((difficultyMask & 8) != 0) {
            candidates.addAll(codeforcesService.div2EduContestList);
        }
        if ((difficultyMask & 16) != 0) {
            candidates.addAll(codeforcesService.div12ContestList);
        }
        if ((difficultyMask & 32) != 0) {
            candidates.addAll(codeforcesService.div1ContestList);
        }

        candidates.removeIf(contest -> contests.contains(contest.id()));
        Collections.shuffle(candidates);

        return SearchResponse.builder()
                .contestList(candidates.stream().limit(3).toList())
                .build();
    }
}
