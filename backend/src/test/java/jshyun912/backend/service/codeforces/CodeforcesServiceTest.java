package jshyun912.backend.service.codeforces;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.spy;

import jshyun912.backend.dto.codeforces.CodeforcesResponse;
import jshyun912.backend.dto.codeforces.Contest;
import jshyun912.backend.dto.codeforces.Submission;
import jshyun912.backend.exception.ApiException;
import jshyun912.backend.exception.ErrorMessage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.ArgumentMatchers;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("CodeforcesService")
class CodeforcesServiceTest {
    private static final String HANDLE = "tourist";
    private static final String USER_STATUS_URI = "https://codeforces.com/api/user.status?handle={handle}";

    @TempDir
    private Path divisionsDirectory;

    @Mock
    private JsonMapper jsonMapper;

    @Mock
    private RestClient restClient;

    @Mock
    private RestClient.RequestHeadersUriSpec<?> requestHeadersUriSpec;

    @Mock
    private RestClient.RequestHeadersSpec<?> requestHeadersSpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    private CodeforcesService codeforcesService;

    @BeforeEach
    void setUp() {
        codeforcesService = new CodeforcesService(restClient, jsonMapper);
    }

    @Nested
    @DisplayName("getSubmissions를 호출하면")
    class GetSubmissions {

    @Nested
    @DisplayName("Codeforces user.status API 응답이 주어지면")
    class GivenCodeforcesResponse {

        @BeforeEach
        void setUpRequest() {
            willReturn(requestHeadersUriSpec).given(restClient).get();
            willReturn(requestHeadersSpec).given(requestHeadersUriSpec).uri(USER_STATUS_URI, HANDLE);
            given(requestHeadersSpec.retrieve()).willReturn(responseSpec);
            willReturn(responseSpec).given(responseSpec)
                    .onStatus(ArgumentMatchers.any(), ArgumentMatchers.any());
        }

        @Test
        @DisplayName("성공 응답을 반환한다")
        void thenReturnsSuccessfulResponse() {
            // given
            CodeforcesResponse<List<Submission>> response = new CodeforcesResponse<>("OK", null, List.of());
            willReturn(response).given(responseSpec)
                    .body(ArgumentMatchers.<ParameterizedTypeReference<CodeforcesResponse<List<Submission>>>>any());

            // when
            CodeforcesResponse<List<Submission>> result = codeforcesService.getSubmissions(HANDLE);

            // then
            assertSame(response, result);
            then(requestHeadersUriSpec).should().uri(USER_STATUS_URI, HANDLE);
        }

        @Test
        @DisplayName("존재하지 않는 handle이면 사용자 조회 예외를 던진다")
        void thenThrowsUserNotFoundWhenHandleDoesNotExist() {
            // given
            CodeforcesResponse<List<Submission>> response = new CodeforcesResponse<>(
                    "FAILED",
                    "handle: User with handle tourist not found",
                    null
            );
            willReturn(response).given(responseSpec)
                    .body(ArgumentMatchers.<ParameterizedTypeReference<CodeforcesResponse<List<Submission>>>>any());

            // when
            ApiException exception = assertThrows(ApiException.class, () -> codeforcesService.getSubmissions(HANDLE));

            // then
            assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatus());
            assertEquals(ErrorMessage.CODEFORCES_USER_NOT_FOUND, exception.getErrorMessage());
            assertEquals("Codeforces 사용자 " + HANDLE + "을 찾을 수 없습니다.", exception.getDetail());
        }

        @Test
        @DisplayName("그 밖의 실패 응답이면 Codeforces API 예외를 던진다")
        void thenThrowsApiExceptionWhenCodeforcesReturnsOtherFailure() {
            // given
            CodeforcesResponse<List<Submission>> response = new CodeforcesResponse<>(
                    "FAILED",
                    "Call limit exceeded",
                    null
            );
            willReturn(response).given(responseSpec)
                    .body(ArgumentMatchers.<ParameterizedTypeReference<CodeforcesResponse<List<Submission>>>>any());

            // when
            ApiException exception = assertThrows(ApiException.class, () -> codeforcesService.getSubmissions(HANDLE));

            // then
            assertEquals(HttpStatus.BAD_GATEWAY, exception.getHttpStatus());
            assertEquals(ErrorMessage.CODEFORCES_API_FAILED, exception.getErrorMessage());
            assertEquals("Codeforces API 요청에 실패했습니다: Call limit exceeded", exception.getDetail());
        }

        @Test
        @DisplayName("result가 없으면 Codeforces API 예외를 던진다")
        void thenThrowsApiExceptionWhenResultIsNull() {
            // given
            CodeforcesResponse<List<Submission>> response = new CodeforcesResponse<>("OK", null, null);
            willReturn(response).given(responseSpec)
                    .body(ArgumentMatchers.<ParameterizedTypeReference<CodeforcesResponse<List<Submission>>>>any());

            // when
            ApiException exception = assertThrows(ApiException.class, () -> codeforcesService.getSubmissions(HANDLE));

            // then
            assertEquals(HttpStatus.BAD_GATEWAY, exception.getHttpStatus());
            assertEquals(ErrorMessage.CODEFORCES_API_FAILED, exception.getErrorMessage());
            assertEquals("Codeforces API 응답이 올바르지 않습니다.", exception.getDetail());
        }

        @Test
        @DisplayName("응답이 없으면 Codeforces API 예외를 던진다")
        void thenThrowsApiExceptionWhenResponseIsNull() {
            // given
            willReturn(null).given(responseSpec)
                    .body(ArgumentMatchers.<ParameterizedTypeReference<CodeforcesResponse<List<Submission>>>>any());

            // when
            ApiException exception = assertThrows(ApiException.class, () -> codeforcesService.getSubmissions(HANDLE));

            // then
            assertEquals(HttpStatus.BAD_GATEWAY, exception.getHttpStatus());
            assertEquals(ErrorMessage.CODEFORCES_API_FAILED, exception.getErrorMessage());
            assertEquals("Codeforces API 응답이 없습니다.", exception.getDetail());
        }
    }

    }

    @Nested
    @DisplayName("Codeforces 요청 중 통신 예외가 발생하면")
    class GivenRestClientException {

        @BeforeEach
        void setUpRequest() {
            willReturn(requestHeadersUriSpec).given(restClient).get();
            willReturn(requestHeadersSpec).given(requestHeadersUriSpec).uri(USER_STATUS_URI, HANDLE);
            willThrow(new RestClientException("connection failed")).given(requestHeadersSpec).retrieve();
        }

        @Test
        @DisplayName("Codeforces API 예외로 변환하고 원인을 보존한다")
        void thenThrowsApiExceptionWithCause() {
            // when
            ApiException exception = assertThrows(ApiException.class, () -> codeforcesService.getSubmissions(HANDLE));

            // then
            assertEquals(HttpStatus.BAD_GATEWAY, exception.getHttpStatus());
            assertEquals(ErrorMessage.CODEFORCES_API_FAILED, exception.getErrorMessage());
            assertEquals("핸들 " + HANDLE + "의 Codeforces user.status 요청에 실패했습니다.", exception.getDetail());
            assertEquals("connection failed", exception.getCause().getMessage());
        }
    }

    @Nested
    @DisplayName("getParticipateContest를 호출하면")
    class GetParticipateContest {

        @Test
        @DisplayName("중복을 제거한 대회 ID 집합을 반환한다")
        void thenReturnsUniqueContestIds() {
            // given
            CodeforcesService codeforcesServiceSpy = spy(codeforcesService);
            CodeforcesResponse<List<Submission>> response = new CodeforcesResponse<>("OK", null,
                    List.of(submission(100), submission(200), submission(100)));
            willReturn(response).given(codeforcesServiceSpy).getSubmissions(HANDLE);

            // when
            Set<Integer> result = codeforcesServiceSpy.getParticipateContest(HANDLE);

            // then
            assertEquals(Set.of(100, 200), result);
            then(codeforcesServiceSpy).should().getSubmissions(HANDLE);
        }

        @Test
        @DisplayName("제출 목록이 비어 있으면 빈 집합을 반환한다")
        void thenReturnsEmptySetWhenSubmissionsAreEmpty() {
            // given
            CodeforcesService codeforcesServiceSpy = spy(codeforcesService);
            CodeforcesResponse<List<Submission>> response = new CodeforcesResponse<>("OK", null, List.of());
            willReturn(response).given(codeforcesServiceSpy).getSubmissions(HANDLE);

            // when
            Set<Integer> result = codeforcesServiceSpy.getParticipateContest(HANDLE);

            // then
            assertEquals(Set.of(), result);
            then(codeforcesServiceSpy).should().getSubmissions(HANDLE);
        }

        @Test
        @DisplayName("제출 조회가 실패하면 예외를 그대로 전파한다")
        void thenPropagatesApiException() {
            // given
            CodeforcesService codeforcesServiceSpy = spy(codeforcesService);
            ApiException expected = new ApiException(HttpStatus.BAD_GATEWAY,
                    ErrorMessage.CODEFORCES_API_FAILED, "Codeforces API 요청에 실패했습니다.");
            willThrow(expected).given(codeforcesServiceSpy).getSubmissions(HANDLE);

            // when
            ApiException exception = assertThrows(ApiException.class,
                    () -> codeforcesServiceSpy.getParticipateContest(HANDLE));

            // then
            assertSame(expected, exception);
            then(codeforcesServiceSpy).should().getSubmissions(HANDLE);
        }
    }

    private Submission submission(Integer contestId) {
        return new Submission(null, contestId, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Nested
    @DisplayName("initContestList를 호출하면")
    class InitContestList {

        @Test
        @DisplayName("각 division JSON의 대회를 해당 리스트에 로드한다")
        void thenLoadsContestsIntoEachDivisionList() throws IOException {
            // given
            JsonMapper realJsonMapper = JsonMapper.builder().build();
            Contest div1Contest = contest(100);
            Contest div12Contest = contest(101);
            Contest div2Contest = contest(102);
            Contest div2EduContest = contest(103);
            Contest div3Contest = contest(104);
            Contest div4Contest = contest(105);
            Contest otherContest = contest(106);
            writeDivisionFile(realJsonMapper, "Div1.json", List.of(div1Contest));
            writeDivisionFile(realJsonMapper, "Div1Plus2.json", List.of(div12Contest));
            writeDivisionFile(realJsonMapper, "Div2.json", List.of(div2Contest));
            writeDivisionFile(realJsonMapper, "Div2Educational.json", List.of(div2EduContest));
            writeDivisionFile(realJsonMapper, "Div3.json", List.of(div3Contest));
            writeDivisionFile(realJsonMapper, "Div4.json", List.of(div4Contest));
            writeDivisionFile(realJsonMapper, "others.json", List.of(otherContest));
            CodeforcesService service = new CodeforcesService(restClient, realJsonMapper);
            service.setDivisionsDirectory(divisionsDirectory);

            // when
            service.initContestList();

            // then
            assertEquals(List.of(div1Contest), service.div1ContestList);
            assertEquals(List.of(div12Contest), service.div12ContestList);
            assertEquals(List.of(div2Contest), service.div2ContestList);
            assertEquals(List.of(div2EduContest), service.div2EduContestList);
            assertEquals(List.of(div3Contest), service.div3ContestList);
            assertEquals(List.of(div4Contest), service.div4ContestList);
            assertEquals(List.of(otherContest), service.otherContestList);
        }

        @Test
        @DisplayName("division JSON 파일이 없으면 대회 목록 로드 예외를 던진다")
        void thenThrowsLoadExceptionWhenDivisionFileIsMissing() {
            // given
            JsonMapper realJsonMapper = JsonMapper.builder().build();
            CodeforcesService service = new CodeforcesService(restClient, realJsonMapper);
            service.setDivisionsDirectory(divisionsDirectory);

            // when
            ApiException exception = assertThrows(ApiException.class, service::initContestList);

            // then
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getHttpStatus());
            assertEquals(ErrorMessage.CONTEST_DATA_LOAD_FAILED, exception.getErrorMessage());
            assertEquals("대회 목록 파일 " + divisionsDirectory.resolve("Div1.json") + "을 불러오지 못했습니다.",
                    exception.getDetail());
        }

        @Test
        @DisplayName("잘못된 division JSON 파일이면 대회 목록 로드 예외를 던진다")
        void thenThrowsLoadExceptionWhenDivisionFileIsInvalid() throws IOException {
            // given
            Path divisionFile = divisionsDirectory.resolve("Div1.json");
            Files.writeString(divisionFile, "invalid json");
            CodeforcesService service = new CodeforcesService(restClient, JsonMapper.builder().build());
            service.setDivisionsDirectory(divisionsDirectory);

            // when
            ApiException exception = assertThrows(ApiException.class, service::initContestList);

            // then
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getHttpStatus());
            assertEquals(ErrorMessage.CONTEST_DATA_LOAD_FAILED, exception.getErrorMessage());
            assertEquals("대회 목록 파일 " + divisionFile + "을 불러오지 못했습니다.", exception.getDetail());
        }
    }

    private void writeDivisionFile(JsonMapper jsonMapper, String fileName, List<Contest> contests) throws IOException {
        try (OutputStream outputStream = Files.newOutputStream(divisionsDirectory.resolve(fileName))) {
            jsonMapper.writeValue(outputStream, new CodeforcesResponse<>("OK", null, contests));
        }
    }

    private Contest contest(Integer id) {
        return new Contest(id, "Codeforces Round", "ICPC", "FINISHED", false,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
