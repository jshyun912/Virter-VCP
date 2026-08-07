package jshyun912.backend.dto.codeforces;

public record RatingChange(
        Integer contestId,
        String contestName,
        String handle,
        Integer rank,
        Integer ratingUpdateTimeSeconds,
        Integer oldRating,
        Integer newRating
) {
}
