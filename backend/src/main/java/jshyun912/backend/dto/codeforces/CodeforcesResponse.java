package jshyun912.backend.dto.codeforces;

/**
 * Codeforces API의 공통 응답 형식이다.
 *
 * @param status API 처리 결과. 성공하면 {@code OK}, 실패하면 {@code FAILED}
 * @param comment 실패 사유. 성공 응답에서는 없을 수 있음
 * @param result API 메서드별 응답 데이터. 실패 응답에서는 없을 수 있음
 * @param <T> API 메서드가 반환하는 데이터 타입
 */
public record CodeforcesResponse<T>(
        String status,
        String comment,
        T result
) {
}
