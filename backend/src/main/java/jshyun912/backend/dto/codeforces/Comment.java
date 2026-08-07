package jshyun912.backend.dto.codeforces;

public record Comment(
        Integer id,
        Integer creationTimeSeconds,
        String commentatorHandle,
        String locale,
        String text,
        Integer parentCommentId,
        Integer rating
) {
}
