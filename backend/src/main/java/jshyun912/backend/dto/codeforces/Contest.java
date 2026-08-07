package jshyun912.backend.dto.codeforces;

public record Contest(
        Integer id,
        String name,
        String type,
        String phase,
        Boolean frozen,
        Integer durationSeconds,
        Integer freezeDurationSeconds,
        Integer startTimeSeconds,
        Integer relativeTimeSeconds,
        String preparedBy,
        String websiteUrl,
        String description,
        Integer difficulty,
        String kind,
        String icpcRegion,
        String country,
        String city,
        String season
) {
}
