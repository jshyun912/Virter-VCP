package jshyun912.backend.dto.codeforces;

import java.util.List;

public record RanklistRow(
        Party party,
        Integer rank,
        Double points,
        Integer penalty,
        Integer successfulHackCount,
        Integer unsuccessfulHackCount,
        List<ProblemResult> problemResults,
        Integer lastSubmissionTimeSeconds
) {
}
