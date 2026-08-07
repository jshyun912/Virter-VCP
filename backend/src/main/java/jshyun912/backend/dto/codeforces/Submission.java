package jshyun912.backend.dto.codeforces;

public record Submission(
        Integer id,
        Integer contestId,
        Integer creationTimeSeconds,
        Integer relativeTimeSeconds,
        Problem problem,
        Party author,
        String programmingLanguage,
        String verdict,
        String testset,
        Integer passedTestCount,
        Integer timeConsumedMillis,
        Integer memoryConsumedBytes,
        Double points
) {
}
