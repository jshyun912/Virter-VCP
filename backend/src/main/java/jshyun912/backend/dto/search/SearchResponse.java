package jshyun912.backend.dto.search;

import jshyun912.backend.dto.codeforces.Contest;
import lombok.Builder;

import java.util.List;

/**
 * Virtual Contest 추천 결과다.
 *
 * @param contestList 요청에서 선택한 디비전에 속하고, 입력 핸들 중 누구도 참여하지 않은 대회 중
 *                    무작위로 선택한 최대 세 개의 후보
 */
@Builder
public record SearchResponse(
        List<Contest> contestList
) {

}
