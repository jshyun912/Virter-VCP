package jshyun912.backend.dto.codeforces;

import java.util.List;

public record Problem(
        Integer contestId,
        String problemsetName,
        String index,
        String name,
        String type,
        Double points,
        Integer rating,
        List<String> tags
) {
}
