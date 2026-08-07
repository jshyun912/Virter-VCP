package jshyun912.backend.dto.codeforces;

public record User(
        String handle,
        String email,
        String vkId,
        String openId,
        String firstName,
        String lastName,
        String country,
        String city,
        String organization,
        Integer contribution,
        String rank,
        Integer rating,
        String maxRank,
        Integer maxRating,
        Integer lastOnlineTimeSeconds,
        Integer registrationTimeSeconds,
        Integer friendOfCount,
        String avatar,
        String titlePhoto
) {
}
