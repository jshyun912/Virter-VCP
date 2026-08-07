package jshyun912.backend.dto.codeforces;

import java.util.Map;

public record Hack(
        Integer id,
        Integer creationTimeSeconds,
        Party hacker,
        Party defender,
        String verdict,
        Problem problem,
        String test,
        Map<String, String> judgeProtocol
) {
}
