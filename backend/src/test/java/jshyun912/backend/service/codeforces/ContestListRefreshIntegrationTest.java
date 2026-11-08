package jshyun912.backend.service.codeforces;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jshyun912.backend.dto.codeforces.Contest;
import jshyun912.backend.exception.ApiException;
import jshyun912.backend.exception.ErrorMessage;
import jshyun912.backend.service.RedisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;
import tools.jackson.core.exc.StreamWriteException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Tag("integration")
@ExtendWith(MockitoExtension.class)
@DisplayName("대회 목록 파일 갱신")
class ContestListRefreshIntegrationTest {
    private static final String CONTEST_LIST_URI = "https://codeforces.com/api/contest.list?gym=false";
    private static final List<String> DIVISION_FILES = List.of("Div1.json", "Div1Plus2.json", "Div2.json",
            "Div2Educational.json", "Div3.json", "Div4.json", "others.json");
    private static final String UPDATED_CONTESTS = """
            [{"id":100,"name":"Codeforces Round 100 (Div. 2)","phase":"FINISHED"},
             {"id":101,"name":"Codeforces Round 101 (Div. 2)","phase":"FINISHED"}]
            """;
    private static final String LOCAL_CONTEST =
            "{\"id\":90,\"name\":\"Codeforces Round 90 (Div. 2)\",\"phase\":\"FINISHED\"}";

    @TempDir
    private Path divisionsDirectory;

    @Mock
    private RedisService redisService;

    private final JsonMapper jsonMapper = spy(JsonMapper.builder().build());
    private MockRestServiceServer server;
    private CodeforcesService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new CodeforcesService(builder.build(), jsonMapper, redisService);
        service.setDivisionsDirectory(divisionsDirectory);
    }

    @Test
    @DisplayName("매일 한국 시간 낮 열두 시에 목록 갱신을 예약한다")
    void thenSchedulesRefreshAtNoonInKorea() throws NoSuchMethodException {
        // Given
        Scheduled scheduled = CodeforcesService.class.getMethod("refreshContestList").getAnnotation(Scheduled.class);

        // When / Then
        assertNotNull(scheduled);
        assertEquals("Asia/Seoul", scheduled.zone());
        assertEquals(LocalDateTime.of(2026, 11, 9, 12, 0),
                CronExpression.parse(scheduled.cron()).next(LocalDateTime.of(2026, 11, 9, 11, 59)));
        assertEquals(LocalDateTime.of(2026, 11, 10, 12, 0),
                CronExpression.parse(scheduled.cron()).next(LocalDateTime.of(2026, 11, 9, 12, 0)));
    }

    @Nested
    @DisplayName("정상인 대회 JSON 일곱 개가 있으면")
    class GivenValidLocalFiles {
        @BeforeEach
        void setUpFiles() throws IOException {
            for (String fileName : DIVISION_FILES) {
                writeDivisionFile(fileName, "[]");
            }
        }

        @Test
        @DisplayName("시작할 때 일반 대회를 조회하고 종료한 대회만 해당 파일에 저장한다")
        void thenRefreshesFinishedContestsAtStartup() throws IOException {
            // Given
            expectResponse(withSuccess("""
                    {"status":"OK","result":[
                      {"id":100,"name":"Codeforces Round 100 (Div. 2)","phase":"FINISHED"},
                      {"id":101,"name":"Codeforces Round 101 (Div. 2)","phase":"BEFORE"},
                      {"id":102,"name":"Codeforces Round 102 (Div. 2)","phase":"CODING"},
                      {"id":103,"name":"Codeforces Round 103 (Div. 2)","phase":"PENDING_SYSTEM_TEST"},
                      {"id":104,"name":"Codeforces Round 104 (Div. 2)","phase":"SYSTEM_TEST"}
                    ]}
                    """, MediaType.APPLICATION_JSON));

            // When
            service.initContestList();

            // Then
            assertEquals(List.of(100), savedIds("Div2.json"));
            assertEquals(List.of(100), candidateIds(4));
            server.verify();
        }

        @Test
        @DisplayName("대회 수가 같으면 원본 JSON을 그대로 두고 메모리에 읽은 목록을 사용한다")
        void thenSkipsFileWritesWhenCountMatches() throws IOException {
            // Given
            Files.writeString(divisionsDirectory.resolve("Div2.json"),
                    "{  \"status\": \"OK\", \"result\": [" + LOCAL_CONTEST + "] }\n");
            Map<String, String> originalFiles = readAllFiles();
            expectResponse(successfulContestResponse("[" + LOCAL_CONTEST + "]"));

            // When
            service.initContestList();

            // Then
            assertEquals(originalFiles, readAllFiles());
            assertEquals(List.of(90), candidateIds(4));
            server.verify();
        }

        @Test
        @DisplayName("대회 수가 늘어나면 기존 대회를 유지하고 새 대회를 분류해 추가한다")
        void thenClassifiesContestsExclusivelyAndRefreshesCandidates() throws IOException {
            // Given
            writeDivisionFile("Div2.json", "[" + LOCAL_CONTEST + "]");
            expectResponse(withSuccess("""
                    {"status":"OK","result":[
                      {"id":1,"name":"Codeforces Round 1 (Div. 1)","phase":"FINISHED"},
                      {"id":2,"name":"Codeforces Round 2 (Div. 1 + Div. 2)","phase":"FINISHED"},
                      {"id":3,"name":"Codeforces Round 3 (Div. 2)","phase":"FINISHED"},
                      {"id":4,"name":"Educational Codeforces Round 4 (Rated for Div. 2)","phase":"FINISHED"},
                      {"id":5,"name":"Codeforces Round 5 (Div. 3)","phase":"FINISHED"},
                      {"id":6,"name":"Codeforces Round 6 (Div. 4)","phase":"FINISHED"},
                      {"id":7,"name":"Codeforces Global Round 7","phase":"FINISHED"}
                    ]}
                    """, MediaType.APPLICATION_JSON));

            // When
            service.initContestList();

            // Then
            assertEquals(List.of(1), savedIds("Div1.json"));
            assertEquals(List.of(2), savedIds("Div1Plus2.json"));
            assertEquals(List.of(90, 3), savedIds("Div2.json"));
            assertEquals(List.of(4), savedIds("Div2Educational.json"));
            assertEquals(List.of(5), savedIds("Div3.json"));
            assertEquals(List.of(6), savedIds("Div4.json"));
            assertEquals(List.of(7), savedIds("others.json"));
            assertEquals(List.of(6), candidateIds(1));
            assertEquals(List.of(5), candidateIds(2));
            assertEquals(List.of(90, 3), candidateIds(4));
            assertEquals(List.of(4), candidateIds(8));
            assertEquals(List.of(2), candidateIds(16));
            assertEquals(List.of(1), candidateIds(32));
            assertEquals(7, service.getContestCandidates(63).size());
            assertTrue(service.getContestCandidates(64).isEmpty());
            server.verify();
        }

        @Test
        @DisplayName("기존 ID의 정보와 분류를 보존하고 응답에 중복된 새 ID도 한 번만 추가한다")
        void thenPreservesExistingContestsAndAddsOnlyUnseenIds() throws IOException {
            // Given
            writeDivisionFile("Div2.json", "[" + LOCAL_CONTEST + "]");
            writeDivisionFile("others.json", """
                    [{"id":91,"name":"Codeforces Global Round 91","phase":"FINISHED"}]
                    """);
            expectResponse(withSuccess("""
                    {"status":"OK","result":[
                      {"id":90,"name":"Renamed Round (Div. 1)","phase":"FINISHED"},
                      {"id":91,"name":"Renamed Round (Div. 2)","phase":"FINISHED"},
                      {"id":100,"name":"New Round (Div. 2)","phase":"FINISHED"},
                      {"id":100,"name":"New Round (Div. 2)","phase":"FINISHED"},
                      {"id":101,"name":"New Round (Div. 3)","phase":"FINISHED"}
                    ]}
                    """, MediaType.APPLICATION_JSON));

            // When
            service.initContestList();

            // Then
            assertEquals(List.of(90, 100), savedIds("Div2.json"));
            assertEquals(List.of(90, 100), candidateIds(4));
            assertEquals("Codeforces Round 90 (Div. 2)", service.getContestCandidates(4).getFirst().name());
            assertTrue(savedIds("Div1.json").isEmpty());
            assertEquals(List.of(91), savedIds("others.json"));
            assertEquals(List.of(101), savedIds("Div3.json"));
            assertEquals(List.of(101), candidateIds(2));
            server.verify();
        }

        @Test
        @DisplayName("각 파일의 기존 대회를 API 장애 시에도 해당 디비전 메모리에 로드한다")
        void thenLoadsAllLocalDivisionsWhenStartupApiFails() throws IOException {
            // Given
            for (int i = 0; i < DIVISION_FILES.size(); i++) {
                writeDivisionFile(DIVISION_FILES.get(i), "[{\"id\":" + (100 + i)
                        + ",\"name\":\"Local contest\",\"phase\":\"FINISHED\"}]");
            }
            Map<String, String> originalFiles = readAllFiles();
            expectResponse(withServerError());

            // When
            assertDoesNotThrow(service::initContestList);

            // Then
            assertEquals(List.of(100), candidateIds(32));
            assertEquals(List.of(101), candidateIds(16));
            assertEquals(List.of(102), candidateIds(4));
            assertEquals(List.of(103), candidateIds(8));
            assertEquals(List.of(104), candidateIds(2));
            assertEquals(List.of(105), candidateIds(1));
            assertEquals(originalFiles, readAllFiles());
            server.verify();
        }

        @ParameterizedTest
        @ValueSource(strings = {"invalid json", "{\"status\":\"FAILED\",\"result\":[]}",
                "{\"status\":\"OK\",\"result\":null}", "null"})
        @DisplayName("시작 시 API 응답이 잘못되면 기존 파일과 메모리로 서버를 시작한다")
        void thenKeepsLocalFilesWhenStartupApiResponseIsInvalid(String response) throws IOException {
            // Given
            writeDivisionFile("Div2.json", "[" + LOCAL_CONTEST + "]");
            Map<String, String> originalFiles = readAllFiles();
            expectResponse(withSuccess(response, MediaType.APPLICATION_JSON));

            // When
            assertDoesNotThrow(service::initContestList);

            // Then
            assertEquals(originalFiles, readAllFiles());
            assertEquals(List.of(90), candidateIds(4));
            server.verify();
        }

        @Test
        @DisplayName("시작 시 API 연결에 실패해도 기존 목록으로 서버를 시작한다")
        void thenKeepsLocalFilesWhenStartupApiConnectionFails() throws IOException {
            // Given
            writeDivisionFile("Div2.json", "[" + LOCAL_CONTEST + "]");
            Map<String, String> originalFiles = readAllFiles();
            expectResponse(withException(new IOException("connection failed")));

            // When
            assertDoesNotThrow(service::initContestList);

            // Then
            assertEquals(originalFiles, readAllFiles());
            assertEquals(List.of(90), candidateIds(4));
            server.verify();
        }

        @ParameterizedTest
        @ValueSource(strings = {"[]", "[" + LOCAL_CONTEST + "]"})
        @DisplayName("시작 시 완료 대회 수가 일곱 JSON의 합보다 적으면 기존 파일과 메모리를 보존한다")
        void thenKeepsLocalFilesWhenStartupContestCountDecreases(String contests) throws IOException {
            // Given
            writeDivisionFile("Div2.json", "[" + LOCAL_CONTEST + "]");
            writeDivisionFile("others.json", """
                    [{"id":91,"name":"Codeforces Global Round 91","phase":"FINISHED"}]
                    """);
            Map<String, String> originalFiles = readAllFiles();
            expectResponse(successfulContestResponse(contests));

            // When
            assertDoesNotThrow(service::initContestList);

            // Then
            assertEquals(originalFiles, readAllFiles());
            assertEquals(List.of(90), candidateIds(4));
            server.verify();
        }

        @ParameterizedTest
        @ValueSource(strings = {"Div2.json", "Div3.json"})
        @DisplayName("로컬 파일 안이나 파일 사이에 ID가 중복되면 ID와 파일명을 알리고 시작을 거부한다")
        void thenRejectsDuplicateLocalIds(String duplicateFile) throws IOException {
            // Given
            writeDivisionFile("Div2.json", "[" + LOCAL_CONTEST + "]");
            writeDivisionFile(duplicateFile, duplicateFile.equals("Div2.json")
                    ? "[" + LOCAL_CONTEST + "," + LOCAL_CONTEST + "]" : "[" + LOCAL_CONTEST + "]");

            // When
            ApiException exception = assertThrows(ApiException.class, service::initContestList);

            // Then
            assertLoadFailure(exception);
            assertTrue(exception.getDetail().contains("90"));
            assertTrue(exception.getDetail().contains("Div2.json"));
            assertTrue(exception.getDetail().contains(duplicateFile));
            assertNotNull(exception.getCause());
            assertEquals(exception.getDetail(), exception.getCause().getMessage());
            server.verify();
        }

        @ParameterizedTest
        @ValueSource(strings = {"BEFORE", "CODING", "PENDING_SYSTEM_TEST", "SYSTEM_TEST", "UNKNOWN"})
        @DisplayName("로컬 대회가 완료 상태가 아니면 API 조회 전에 시작을 거부한다")
        void thenRejectsUnfinishedLocalContest(String phase) throws IOException {
            // Given
            writeDivisionFile("Div2.json", "[{\"id\":90,\"name\":\"Round\",\"phase\":\"" + phase + "\"}]");

            // When
            ApiException exception = assertThrows(ApiException.class, service::initContestList);

            // Then
            assertLoadFailure(exception);
            server.verify();
        }

        @ParameterizedTest
        @ValueSource(strings = {"[]", "null", "[{\"id\":100,\"name\":\"Round\",\"phase\":\"BEFORE\"}]"})
        @DisplayName("초기 조회 후 완료 대회가 하나도 없으면 빈 목록으로 실행하지 않고 시작을 거부한다")
        void thenRejectsEmptyInitialSnapshot(String contests) {
            // Given
            expectResponse(successfulContestResponse(contests));

            // When
            ApiException exception = assertThrows(ApiException.class, service::initContestList);

            // Then
            assertLoadFailure(exception);
            server.verify();
        }

        @Test
        @DisplayName("빈 로컬 목록의 최초 API 조회가 실패하면 시작을 거부한다")
        void thenRejectsEmptySnapshotAfterStartupApiFailure() {
            // Given
            expectResponse(withServerError());

            // When
            ApiException exception = assertThrows(ApiException.class, service::initContestList);

            // Then
            assertLoadFailure(exception);
            assertNotNull(exception.getCause());
            assertTrue(exception.getCause().getMessage().contains("완료 대회"));
            server.verify();
        }

        @Nested
        @DisplayName("파일 교체 도중 프로세스가 중단되어 백업과 pending 표시가 남으면")
        class GivenInterruptedRefresh {
            private Path staging;
            private Map<String, String> originalFiles;

            @BeforeEach
            void prepareInterruptedRefresh() throws IOException {
                writeDivisionFile("Div2.json", "[" + LOCAL_CONTEST + "]");
                originalFiles = readAllFiles();
                staging = Files.createDirectory(divisionsDirectory.resolve(".refresh-interrupted"));
                for (String fileName : DIVISION_FILES) {
                    Files.copy(divisionsDirectory.resolve(fileName), staging.resolve(fileName + ".bak"));
                }
                Files.writeString(staging.resolve("pending"), "");
                writeDivisionFile("Div2.json", UPDATED_CONTESTS);
            }

            @Test
            @DisplayName("로컬 목록을 읽기 전에 이전 버전 전체를 복구하고 API 장애에도 해당 목록으로 시작한다")
            void thenRestoresBeforeLoadingLocalFiles() throws IOException {
                // Given
                Files.writeString(divisionsDirectory.resolve("Div3.json"), "interrupted JSON");
                expectResponse(withServerError());

                // When
                service.initContestList();

                // Then
                assertEquals(originalFiles, readAllFiles());
                assertEquals(List.of(90), candidateIds(4));
                assertFalse(Files.exists(staging));
                server.verify();
            }

            @Test
            @DisplayName("복구 중 실패해도 모든 원본 백업을 남겨 다음 시작에 다시 복구한다")
            void thenRetriesFailedRecoveryWithIntactBackups() throws IOException {
                // Given
                Path blockedFile = divisionsDirectory.resolve("Div3.json");
                Files.delete(blockedFile);
                Files.createDirectory(blockedFile);

                // When
                ApiException exception = assertThrows(ApiException.class, service::initContestList);

                // Then
                assertLoadFailure(exception);
                assertTrue(Files.exists(staging.resolve("pending")));
                for (String fileName : DIVISION_FILES) {
                    assertEquals(originalFiles.get(fileName), Files.readString(staging.resolve(fileName + ".bak")));
                }
                Files.delete(blockedFile);
                expectResponse(withServerError());
                assertDoesNotThrow(service::initContestList);
                assertEquals(originalFiles, readAllFiles());
                assertEquals(List.of(90), candidateIds(4));
                assertFalse(Files.exists(staging));
                server.verify();
            }

            @Test
            @DisplayName("백업이 불완전하면 복구를 완료한 것으로 간주하지 않고 시작을 거부한다")
            void thenRejectsIncompleteBackup() throws IOException {
                // Given
                Files.delete(staging.resolve("others.json.bak"));

                // When
                ApiException exception = assertThrows(ApiException.class, service::initContestList);

                // Then
                assertLoadFailure(exception);
                assertTrue(Files.exists(staging.resolve("pending")));
                assertTrue(Files.exists(staging.resolve("Div2.json.bak")));
                server.verify();
            }

            @Test
            @DisplayName("pending 표시가 없으면 완료된 버전을 유지하고 남은 임시 파일만 정리한다")
            void thenKeepsCommittedFilesWithoutPendingMarker() throws IOException {
                // Given
                Files.delete(staging.resolve("pending"));
                Map<String, String> committedFiles = readAllFiles();
                expectResponse(withServerError());

                // When
                service.initContestList();

                // Then
                assertEquals(committedFiles, readAllFiles());
                assertEquals(List.of(100, 101), candidateIds(4));
                assertFalse(Files.exists(staging));
                server.verify();
            }
        }

        @Nested
        @DisplayName("로컬 파일을 읽을 수 없으면")
        class GivenUnreadableLocalFile {
            @Test
            @DisplayName("일곱 파일 중 하나라도 없으면 API를 호출하기 전에 시작을 거부한다")
            void thenRejectsMissingLocalFileBeforeApiRequest() throws IOException {
                // Given
                Files.delete(divisionsDirectory.resolve("others.json"));

                // When
                ApiException exception = assertThrows(ApiException.class, service::initContestList);

                // Then
                assertLoadFailure(exception);
                server.verify();
            }

            @ParameterizedTest
            @ValueSource(strings = {"invalid json", "{\"status\":\"FAILED\",\"result\":[]}",
                    "{\"status\":\"OK\",\"result\":null}", "null"})
            @DisplayName("잘못된 로컬 JSON이면 API를 호출하기 전에 시작을 거부한다")
            void thenRejectsInvalidLocalFileBeforeApiRequest(String contents) throws IOException {
                // Given
                Files.writeString(divisionsDirectory.resolve("Div3.json"), contents);

                // When
                ApiException exception = assertThrows(ApiException.class, service::initContestList);

                // Then
                assertLoadFailure(exception);
                server.verify();
            }

            @Test
            @DisplayName("JSON 경로를 파일로 읽을 수 없으면 API를 호출하기 전에 시작을 거부한다")
            void thenRejectsUnreadableFileBeforeApiRequest() throws IOException {
                // Given
                Path file = divisionsDirectory.resolve("Div4.json");
                Files.delete(file);
                Files.createDirectory(file);

                // When
                ApiException exception = assertThrows(ApiException.class, service::initContestList);

                // Then
                assertLoadFailure(exception);
                server.verify();
            }
        }

        @Nested
        @DisplayName("기존 목록을 성공적으로 로드한 뒤에는")
        class GivenLoadedSnapshot {
            @BeforeEach
            void loadSnapshot() throws IOException {
                writeDivisionFile("Div2.json", "[" + LOCAL_CONTEST + "]");
                expectResponse(successfulContestResponse("[" + LOCAL_CONTEST + "]"));
                service.initContestList();
                server.verify();
                server.reset();
            }

            @ParameterizedTest
            @ValueSource(strings = {"[]", "[" + LOCAL_CONTEST + "]"})
            @DisplayName("후속 조회의 완료 대회 수가 줄어들면 마지막으로 갱신한 파일과 메모리를 보존한다")
            void thenPreservesLatestSnapshotWhenContestCountDecreases(String contests) throws IOException {
                // Given
                expectResponse(successfulContestResponse(UPDATED_CONTESTS));
                service.refreshContestList();
                server.verify();
                server.reset();
                Map<String, String> updatedFiles = readAllFiles();
                expectResponse(successfulContestResponse(contests));

                // When
                assertDoesNotThrow(service::refreshContestList);

                // Then
                assertEquals(updatedFiles, readAllFiles());
                assertEquals(List.of(90, 100, 101), candidateIds(4));
                server.verify();
            }

            @Test
            @DisplayName("후속 갱신 성공 후 API 장애가 발생해도 갱신한 파일과 메모리를 보존한다")
            void thenPreservesLatestSuccessfulSnapshotAfterNextFailure() throws IOException {
                // Given
                expectResponse(successfulContestResponse(UPDATED_CONTESTS));

                // When
                service.refreshContestList();
                server.verify();
                server.reset();
                Map<String, String> updatedFiles = readAllFiles();
                expectResponse(withServerError());
                assertDoesNotThrow(service::refreshContestList);

                // Then
                assertEquals(List.of(90, 100, 101), candidateIds(4));
                assertEquals(List.of(90, 100, 101), savedIds("Div2.json"));
                assertEquals(updatedFiles, readAllFiles());
                server.verify();
            }

            @Test
            @DisplayName("조회 결과가 기존 ID의 중복뿐이면 파일을 다시 쓰지 않는다")
            void thenSkipsWritesWhenApiContainsNoNewIds() throws IOException {
                // Given
                Map<String, String> originalFiles = readAllFiles();
                expectResponse(successfulContestResponse("[" + LOCAL_CONTEST + "," + LOCAL_CONTEST + "]"));

                // When
                service.refreshContestList();

                // Then
                assertEquals(originalFiles, readAllFiles());
                assertEquals(List.of(90), candidateIds(4));
                server.verify();
            }

            @Test
            @DisplayName("새 JSON을 임시 파일에 저장하지 못하면 원본 파일과 메모리를 보존한다")
            void thenPreservesFilesAndSnapshotWhenStagingFails() throws IOException {
                // Given
                Map<String, String> originalFiles = readAllFiles();
                willThrow(new StreamWriteException(null, "staging write failed"))
                        .given(jsonMapper).writeValue(any(File.class), any());
                expectResponse(successfulContestResponse(UPDATED_CONTESTS));

                // When
                assertDoesNotThrow(service::refreshContestList);

                // Then
                assertEquals(originalFiles, readAllFiles());
                assertEquals(List.of(90), candidateIds(4));
                server.verify();
            }

            @ParameterizedTest
            @ValueSource(strings = {"Div2.json", "Div3.json"})
            @DisplayName("파일 교체 중 실패하면 이미 교체한 파일을 복구하고 이전 메모리를 유지한다")
            void thenRollsBackReplacedFilesWhenLaterMoveFails(String blockedFile) throws IOException {
                // Given
                Map<String, String> originalFiles = readAllFiles();
                willAnswer(invocation -> {
                    invocation.callRealMethod();
                    File stagedFile = invocation.getArgument(0);
                    if (stagedFile.getName().equals("others.json")) {
                        Path blockedMove = stagedFile.toPath().resolveSibling(blockedFile);
                        Files.delete(blockedMove);
                        Files.createDirectory(blockedMove);
                    }
                    return null;
                }).given(jsonMapper).writeValue(any(File.class), any());
                expectResponse(successfulContestResponse(UPDATED_CONTESTS));

                // When
                assertDoesNotThrow(service::refreshContestList);

                // Then
                assertEquals(originalFiles, readAllFiles());
                assertEquals(List.of(90), candidateIds(4));
                server.verify();
            }

            @Test
            @DisplayName("갱신된 JSON에도 원본 파일의 소유자와 접근 권한을 보존한다")
            void thenPreservesFileOwnerAndPermissions() throws IOException {
                // Given
                assumeTrue(Files.getFileStore(divisionsDirectory).supportsFileAttributeView("posix"));
                Path originalFile = divisionsDirectory.resolve("Div2.json");
                var permissions = PosixFilePermissions.fromString("rw-------");
                Files.setPosixFilePermissions(originalFile, permissions);
                var owner = Files.getOwner(originalFile);
                expectResponse(successfulContestResponse(UPDATED_CONTESTS));

                // When
                service.refreshContestList();

                // Then
                assertEquals(List.of(90, 100, 101), savedIds("Div2.json"));
                assertEquals(owner, Files.getOwner(originalFile));
                assertEquals(permissions, Files.getPosixFilePermissions(originalFile));
                server.verify();
            }

            @Test
            @DisplayName("검색 후보를 호출자에게 복사해 반환하여 변경해도 저장된 목록은 유지한다")
            void thenReturnsIndependentMutableCandidateList() {
                // Given
                List<Contest> candidates = service.getContestCandidates(4);

                // When
                assertDoesNotThrow(candidates::clear);

                // Then
                assertEquals(List.of(90), candidateIds(4));
                server.verify();
            }
        }
    }

    private void expectResponse(ResponseCreator response) {
        server.expect(requestTo(CONTEST_LIST_URI)).andExpect(method(HttpMethod.GET)).andRespond(response);
    }

    private ResponseCreator successfulContestResponse(String contests) {
        return withSuccess("{\"status\":\"OK\",\"result\":" + contests + "}", MediaType.APPLICATION_JSON);
    }

    private void writeDivisionFile(String fileName, String contests) throws IOException {
        Files.writeString(divisionsDirectory.resolve(fileName),
                "{\"status\":\"OK\",\"result\":" + contests + "}");
    }

    private Map<String, String> readAllFiles() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        for (String fileName : DIVISION_FILES) {
            files.put(fileName, Files.readString(divisionsDirectory.resolve(fileName)));
        }
        return files;
    }

    private List<Integer> savedIds(String fileName) throws IOException {
        List<Contest> contests = jsonMapper.convertValue(
                jsonMapper.readTree(Files.readString(divisionsDirectory.resolve(fileName))).get("result"),
                new TypeReference<List<Contest>>() { });
        return contests.stream().map(Contest::id).toList();
    }

    private List<Integer> candidateIds(int mask) {
        return service.getContestCandidates(mask).stream().map(Contest::id).toList();
    }

    private void assertLoadFailure(ApiException exception) {
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getHttpStatus());
        assertEquals(ErrorMessage.CONTEST_DATA_LOAD_FAILED, exception.getErrorMessage());
    }
}
