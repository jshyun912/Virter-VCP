package jshyun912.backend.dto.codeforces;

public record RecentAction(
        Integer timeSeconds,
        BlogEntry blogEntry,
        Comment comment
) {
}
