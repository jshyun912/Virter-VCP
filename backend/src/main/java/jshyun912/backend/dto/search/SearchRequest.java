package jshyun912.backend.dto.search;

import java.util.List;

/**
 * Virtual Contest 추천 요청 조건이다.
 *
 * @param handles 추천에 함께 참여할 Codeforces 사용자 핸들 목록. 목록의 누구도 참여하지 않은 대회만 후보가 된다.
 * @param difficultyMask 선택할 디비전 비트마스크. bit 0~6은 각각 Div. 4, Div. 3, Div. 2,
 *                       Div. 2 Educational, Div. 1 + Div. 2, Div. 1, 기타 대회를 뜻한다.
 *                       비트가 1이면 해당 디비전을 선택한다. 기타 대회 비트(64)는 현재 사용하지 않는다.
 */
public record SearchRequest(
        List<String> handles,
        int difficultyMask
) {
}
