package jshyun912.backend.service.codeforces;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;

import jshyun912.backend.dto.codeforces.CodeforcesResponse;
import jshyun912.backend.dto.codeforces.Contest;
import jshyun912.backend.dto.codeforces.Submission;
import jshyun912.backend.exception.ApiException;
import jshyun912.backend.exception.ErrorMessage;
import jshyun912.backend.service.RedisService;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisSystemException;
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
    private RedisService redisService;

    @Mock
    private RestClient.RequestHeadersUriSpec<?> requestHeadersUriSpec;

    @Mock
    private RestClient.RequestHeadersSpec<?> requestHeadersSpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    private CodeforcesService codeforcesService;

    @BeforeEach
    void setUp() {
        codeforcesService = new CodeforcesService(restClient, jsonMapper, redisService);
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

        private static final String LOCK_TOKEN = "request-token";

        @Test
        @DisplayName("캐시가 있으면 락과 Codeforces 조회 없이 반환한다")
        void thenReturnsCachedContestsWithoutLocking() {
            // given
            given(redisService.get(HANDLE)).willReturn(Set.of(0, 100));

            // when
            Set<Integer> result = codeforcesService.getParticipateContest(HANDLE);

            // then
            assertEquals(Set.of(100), result);
            then(restClient).shouldHaveNoInteractions();
            then(redisService).should(never()).lock(ArgumentMatchers.anyString());
            then(redisService).should(never()).add(ArgumentMatchers.anyString(), ArgumentMatchers.anySet());
        }

        @Test
        @DisplayName("0만 저장된 캐시는 제출 기록이 없는 사용자로 처리하고 재조회하지 않는다")
        void thenReturnsEmptyContestsForCachedSentinel() {
            // given
            given(redisService.get(HANDLE)).willReturn(Set.of(0));

            // when
            Set<Integer> result = codeforcesService.getParticipateContest(HANDLE);

            // then
            assertEquals(Set.of(), result);
            then(restClient).shouldHaveNoInteractions();
            then(redisService).should(never()).lock(ArgumentMatchers.anyString());
            then(redisService).should(never()).add(ArgumentMatchers.anyString(), ArgumentMatchers.anySet());
        }

        @Test
        @DisplayName("락을 기다리는 동안 캐시가 채워지면 Codeforces를 조회하지 않는다")
        void thenRechecksCacheAfterLocking() {
            // given
            given(redisService.lock(HANDLE)).willReturn(LOCK_TOKEN);
            given(redisService.get(HANDLE)).willReturn(Set.of()).willReturn(Set.of(0, 200));

            // when
            Set<Integer> result = codeforcesService.getParticipateContest(HANDLE);

            // then
            assertEquals(Set.of(200), result);
            then(restClient).shouldHaveNoInteractions();
            then(redisService).should(never()).add(ArgumentMatchers.anyString(), ArgumentMatchers.anySet());
            then(redisService).should().unlock(HANDLE, LOCK_TOKEN);
        }

        @Test
        @DisplayName("락 대기 시간이 초과되면 Codeforces 조회 없이 503 예외를 반환한다")
        void thenDoesNotFetchWithoutLock() {
            // given
            RuntimeException expected = new CannotAcquireLockException("timeout");
            given(redisService.lock(HANDLE)).willThrow(expected);

            // when
            ApiException exception = assertThrows(ApiException.class,
                    () -> codeforcesService.getParticipateContest(HANDLE));

            // then
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getHttpStatus());
            assertEquals(ErrorMessage.SEARCH_TEMPORARILY_UNAVAILABLE, exception.getErrorMessage());
            assertSame(expected, exception.getCause());
            then(restClient).shouldHaveNoInteractions();
            then(redisService).should(never()).unlock(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("메모리 부족으로 락을 저장할 수 없으면 직접 조회 없이 503 예외를 반환한다")
        void thenRejectsSearchWhenLockCannotBeStored() {
            // given
            given(redisService.get(HANDLE)).willReturn(Set.of());
            RedisSystemException expected = new RedisSystemException("OOM command not allowed", null);
            given(redisService.lock(HANDLE)).willThrow(expected);

            // when
            ApiException exception = assertThrows(ApiException.class,
                    () -> codeforcesService.getParticipateContest(HANDLE));

            // then
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getHttpStatus());
            assertEquals(ErrorMessage.SEARCH_TEMPORARILY_UNAVAILABLE, exception.getErrorMessage());
            assertSame(expected, exception.getCause());
            then(restClient).shouldHaveNoInteractions();
            then(redisService).should(never()).unlock(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("Redis 조회 명령이 실패하면 직접 조회 없이 503 예외를 반환한다")
        void thenRejectsSearchWhenCacheReadFails() {
            // given
            RedisSystemException expected = new RedisSystemException("Redis command failed", null);
            given(redisService.get(HANDLE)).willThrow(expected);

            // when
            ApiException exception = assertThrows(ApiException.class,
                    () -> codeforcesService.getParticipateContest(HANDLE));

            // then
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getHttpStatus());
            assertEquals(ErrorMessage.SEARCH_TEMPORARILY_UNAVAILABLE, exception.getErrorMessage());
            assertSame(expected, exception.getCause());
            then(restClient).shouldHaveNoInteractions();
            then(redisService).should(never()).lock(ArgumentMatchers.anyString());
        }

        @Nested
        @DisplayName("Redis 연결이 끊기면")
        class GivenRedisUnavailable {
            @BeforeEach
            void setUpRedisFailure() {
                given(redisService.get(HANDLE)).willThrow(new DataAccessResourceFailureException("unavailable"));
            }

            @Test
            @DisplayName("Codeforces를 직접 조회하고 캐시에 저장하지 않는다")
            void thenFetchesWithoutRedis() {
                // given
                CodeforcesService serviceSpy = spy(codeforcesService);
                willReturn(new CodeforcesResponse<>("OK", null, List.of(submission(100))))
                        .given(serviceSpy).getSubmissions(HANDLE);

                // when
                Set<Integer> result = serviceSpy.getParticipateContest(HANDLE);

                // then
                assertEquals(Set.of(100), result);
                then(serviceSpy).should().getSubmissions(HANDLE);
                then(redisService).should(never()).lock(ArgumentMatchers.anyString());
                then(redisService).should(never()).add(ArgumentMatchers.anyString(), ArgumentMatchers.anySet());
            }

            @Test
            @DisplayName("직접 조회가 진행 중이면 추가 요청은 503으로 거절하고 완료 후 다시 허용한다")
            void thenLimitsConcurrentFallbackAndReleasesPermit() throws Exception {
                // given
                CodeforcesService serviceSpy = spy(codeforcesService);
                CountDownLatch started = new CountDownLatch(1);
                CountDownLatch released = new CountDownLatch(1);
                willAnswer(invocation -> {
                    started.countDown();
                    assertTrue(released.await(3, TimeUnit.SECONDS));
                    return new CodeforcesResponse<>("OK", null, List.of(submission(100)));
                }).given(serviceSpy).getSubmissions(HANDLE);

                // when
                try (var executor = Executors.newSingleThreadExecutor()) {
                    var running = executor.submit(() -> serviceSpy.getParticipateContest(HANDLE));
                    try {
                        assertTrue(started.await(2, TimeUnit.SECONDS));
                        ApiException rejected = assertThrows(ApiException.class,
                                () -> serviceSpy.getParticipateContest(HANDLE));
                        released.countDown();
                        Set<Integer> first = running.get(2, TimeUnit.SECONDS);
                        Set<Integer> next = serviceSpy.getParticipateContest(HANDLE);

                        // then
                        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, rejected.getHttpStatus());
                        assertEquals(Set.of(100), first);
                        assertEquals(Set.of(100), next);
                        then(serviceSpy).should(org.mockito.Mockito.times(2)).getSubmissions(HANDLE);
                    } finally {
                        released.countDown();
                    }
                }
            }

            @Test
            @DisplayName("직접 조회가 실패해도 다음 요청이 다시 조회할 수 있다")
            void thenReleasesFallbackPermitAfterApiFailure() {
                // given
                CodeforcesService serviceSpy = spy(codeforcesService);
                ApiException expected = new ApiException(HttpStatus.BAD_GATEWAY,
                        ErrorMessage.CODEFORCES_API_FAILED, "upstream failure");
                willThrow(expected).willReturn(new CodeforcesResponse<>("OK", null, List.of(submission(200))))
                        .given(serviceSpy).getSubmissions(HANDLE);

                // when
                ApiException failure = assertThrows(ApiException.class,
                        () -> serviceSpy.getParticipateContest(HANDLE));
                Set<Integer> next = serviceSpy.getParticipateContest(HANDLE);

                // then
                assertSame(expected, failure);
                assertEquals(Set.of(200), next);
            }
        }

        @Test
        @DisplayName("Redis 명령이 시간 초과되면 제한된 직접 조회를 사용한다")
        void thenFallsBackOnRedisTimeout() {
            // given
            given(redisService.get(HANDLE)).willThrow(new QueryTimeoutException("timeout"));
            CodeforcesService serviceSpy = spy(codeforcesService);
            willReturn(new CodeforcesResponse<>("OK", null, List.of(submission(100))))
                    .given(serviceSpy).getSubmissions(HANDLE);

            // when
            Set<Integer> result = serviceSpy.getParticipateContest(HANDLE);

            // then
            assertEquals(Set.of(100), result);
            then(serviceSpy).should().getSubmissions(HANDLE);
        }

        @Nested
        @DisplayName("캐시가 없고 락을 획득하면")
        class GivenCacheMissAndLock {

            @BeforeEach
            void setUpCacheMissAndLock() {
                given(redisService.get(HANDLE)).willReturn(Set.of());
                given(redisService.lock(HANDLE)).willReturn(LOCK_TOKEN);
            }

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
                then(redisService).should().add(HANDLE, Set.of(100, 200));
                then(redisService).should().unlock(HANDLE, LOCK_TOKEN);
            }

            @Test
            @DisplayName("대회 ID가 없는 제출은 참여 기록과 캐시 저장 대상에서 제외한다")
            void thenIgnoresSubmissionsWithoutContestId() {
                // given
                CodeforcesService serviceSpy = spy(codeforcesService);
                willReturn(new CodeforcesResponse<>("OK", null, List.of(submission(null), submission(100))))
                        .given(serviceSpy).getSubmissions(HANDLE);

                // when
                Set<Integer> result = serviceSpy.getParticipateContest(HANDLE);

                // then
                assertEquals(Set.of(100), result);
                then(redisService).should().add(HANDLE, Set.of(100));
                then(redisService).should().unlock(HANDLE, LOCK_TOKEN);
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
                then(redisService).should().add(HANDLE, Set.of());
                then(redisService).should().unlock(HANDLE, LOCK_TOKEN);
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
                then(redisService).should().unlock(HANDLE, LOCK_TOKEN);
            }

            @Test
            @DisplayName("락 해제가 실패해도 원래 Codeforces 예외를 보존한다")
            void thenPreservesApiExceptionWhenUnlockFails() {
                // given
                CodeforcesService serviceSpy = spy(codeforcesService);
                ApiException expected = new ApiException(HttpStatus.BAD_REQUEST,
                        ErrorMessage.CODEFORCES_USER_NOT_FOUND, "존재하지 않는 사용자입니다.");
                willThrow(expected).given(serviceSpy).getSubmissions(HANDLE);
                willThrow(new DataAccessResourceFailureException("unavailable"))
                        .given(redisService).unlock(HANDLE, LOCK_TOKEN);

                // when
                ApiException exception = assertThrows(ApiException.class,
                        () -> serviceSpy.getParticipateContest(HANDLE));

                // then
                assertSame(expected, exception);
                then(redisService).should().unlock(HANDLE, LOCK_TOKEN);
            }

            @Test
            @DisplayName("캐시 저장 중 연결이 끊겨도 재조회 없이 가져온 기록을 반환하고 락을 해제한다")
            void thenReturnsLoadedContestsWhenCacheWriteFails() {
                // given
                CodeforcesService serviceSpy = spy(codeforcesService);
                willReturn(new CodeforcesResponse<>("OK", null, List.of(submission(100))))
                        .given(serviceSpy).getSubmissions(HANDLE);
                willThrow(new DataAccessResourceFailureException("unavailable"))
                        .given(redisService).add(HANDLE, Set.of(100));

                // when
                Set<Integer> result = serviceSpy.getParticipateContest(HANDLE);

                // then
                assertEquals(Set.of(100), result);
                then(serviceSpy).should().getSubmissions(HANDLE);
                then(redisService).should().unlock(HANDLE, LOCK_TOKEN);
            }

            @Test
            @DisplayName("캐시 저장 중 메모리가 부족해도 조회 결과를 반환하고 락을 해제한다")
            void thenReturnsLoadedContestsWhenRedisMemoryIsFull() {
                // given
                CodeforcesService serviceSpy = spy(codeforcesService);
                willReturn(new CodeforcesResponse<>("OK", null, List.of(submission(100))))
                        .given(serviceSpy).getSubmissions(HANDLE);
                willThrow(new RedisSystemException("OOM command not allowed", null))
                        .given(redisService).add(HANDLE, Set.of(100));

                // when
                Set<Integer> result = serviceSpy.getParticipateContest(HANDLE);

                // then
                assertEquals(Set.of(100), result);
                then(serviceSpy).should().getSubmissions(HANDLE);
                then(redisService).should().unlock(HANDLE, LOCK_TOKEN);
            }
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
            CodeforcesService service = new CodeforcesService(restClient, realJsonMapper, redisService);
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
            CodeforcesService service = new CodeforcesService(restClient, realJsonMapper, redisService);
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
            CodeforcesService service = new CodeforcesService(restClient, JsonMapper.builder().build(), redisService);
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
