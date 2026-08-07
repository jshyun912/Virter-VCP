package jshyun912.backend.dto.codeforces;

public record ProblemResult(
        Double points,
        Integer penalty,
        Integer rejectedAttemptCount,
        String type,
        Integer bestSubmissionTimeSeconds
) {
}
