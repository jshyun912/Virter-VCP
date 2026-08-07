package jshyun912.backend.dto.codeforces;

import java.util.List;

public record BlogEntry(
        Integer id,
        String originalLocale,
        Integer creationTimeSeconds,
        String authorHandle,
        String title,
        String content,
        String locale,
        Integer modificationTimeSeconds,
        Boolean allowViewHistory,
        List<String> tags,
        Integer rating
) {
}
