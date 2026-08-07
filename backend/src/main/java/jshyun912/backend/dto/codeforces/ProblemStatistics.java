package jshyun912.backend.dto.codeforces;

public record ProblemStatistics(
        Integer contestId,
        String index,
        Integer solvedCount
) {
}
