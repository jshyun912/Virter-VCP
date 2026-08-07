package jshyun912.backend.dto.codeforces;

import java.util.List;

public record Party(
        Integer contestId,
        List<Member> members,
        String participantType,
        Integer teamId,
        String teamName,
        Boolean ghost,
        Integer room,
        Integer startTimeSeconds
) {
}
