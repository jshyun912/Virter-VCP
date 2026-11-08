package jshyun912.backend.service.codeforces;

import jakarta.annotation.PostConstruct;
import jshyun912.backend.dto.codeforces.CodeforcesResponse;
import jshyun912.backend.dto.codeforces.Contest;
import jshyun912.backend.dto.codeforces.Submission;
import jshyun912.backend.exception.ApiException;
import jshyun912.backend.exception.ErrorMessage;
import jshyun912.backend.service.RedisService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.core.type.TypeReference;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Service
@Slf4j
@RequiredArgsConstructor
public class CodeforcesService {
    private static final Path DEFAULT_DIVISIONS_DIRECTORY = Path.of("data", "divisions");
    private static final Duration LOCK_WAIT = Duration.ofSeconds(40);
    private static final Pattern DIVISION_PATTERN =
            Pattern.compile("\\bdiv\\.?\\s*([1-4])\\b", Pattern.CASE_INSENSITIVE);

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final RedisService redisService;
    private final Semaphore fallbackPermit = new Semaphore(1);
    @Value("${app.divisions-directory:data/divisions}")
    @Setter(AccessLevel.PACKAGE)
    private Path divisionsDirectory = DEFAULT_DIVISIONS_DIRECTORY;

    private volatile Map<Division, List<Contest>> contestLists = Map.of();

    @RequiredArgsConstructor
    private enum Division {
        DIV1("Div1.json", 32),
        DIV12("Div1Plus2.json", 16),
        DIV2("Div2.json", 4),
        EDUCATIONAL("Div2Educational.json", 8),
        DIV3("Div3.json", 2),
        DIV4("Div4.json", 1),
        OTHER("others.json", 0);

        private final String fileName;
        private final int mask;
    }

    public CodeforcesResponse<List<Submission>> getSubmissions(String handle) {
        try {
            CodeforcesResponse<List<Submission>> response = restClient.get()
                    .uri("https://codeforces.com/api/user.status?handle={handle}", handle)
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.BAD_REQUEST.value(), (request, errorResponse) -> {
                    })
                    .body(new ParameterizedTypeReference<>() {
                    });
            return requireSuccessfulResponse(response, handle);
        } catch (RestClientException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorMessage.CODEFORCES_API_FAILED,
                    "핸들 " + handle + "의 Codeforces user.status 요청에 실패했습니다.", exception);
        }
    }

    public Set<Integer> getParticipateContest(String handle) {
        try {
            return getCachedOrLoadContests(handle);
        } catch (DataAccessResourceFailureException | QueryTimeoutException exception) {
            log.warn("Redis에 연결할 수 없어 핸들 {}을 직접 조회합니다.", handle, exception);
            return getContestsWithoutCache(handle);
        } catch (DataAccessException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorMessage.SEARCH_TEMPORARILY_UNAVAILABLE,
                    "캐시를 사용할 수 없어 검색을 처리하지 못했습니다. 잠시 후 다시 시도해주세요.", exception);
        }
    }

    private Set<Integer> getCachedOrLoadContests(String handle) {
        long startedAt = System.nanoTime();
        do {
            Set<Integer> cached = redisService.get(handle);
            if (!cached.isEmpty()) {
                return withoutCacheMarker(cached);
            }

            String lockToken = redisService.tryLock(handle);
            if (lockToken != null) {
                return getContestsWithLock(handle, lockToken);
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new CannotAcquireLockException("캐시 락 대기가 중단되었습니다.", exception);
            }
        } while (System.nanoTime() - startedAt < LOCK_WAIT.toNanos());

        throw new CannotAcquireLockException("캐시 락 대기 시간을 초과했습니다.");
    }

    private Set<Integer> getContestsWithLock(String handle, String lockToken) {
        try {
            Set<Integer> cached = redisService.get(handle);
            if (!cached.isEmpty()) {
                return withoutCacheMarker(cached);
            }

            Set<Integer> contests = loadParticipateContests(handle);
            try {
                redisService.add(handle, contests);
            } catch (DataAccessException exception) {
                log.warn("핸들 {}의 제출 기록을 캐시에 저장하지 못했습니다.", handle, exception);
            }
            return contests;
        } finally {
            try {
                redisService.unlock(handle, lockToken);
            } catch (DataAccessException exception) {
                // 해제에 실패한 락은 TTL로 만료시키고 기존 결과나 예외를 보존한다.
                log.warn("핸들 {}의 캐시 락을 해제하지 못했습니다.", handle, exception);
            }
        }
    }

    private Set<Integer> getContestsWithoutCache(String handle) {
        if (!fallbackPermit.tryAcquire()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorMessage.SEARCH_TEMPORARILY_UNAVAILABLE, "현재 직접 조회가 진행 중입니다. 잠시 후 다시 시도해주세요.");
        }
        try {
            return loadParticipateContests(handle);
        } finally {
            fallbackPermit.release();
        }
    }

    private Set<Integer> loadParticipateContests(String handle) {
        Set<Integer> contests = new HashSet<>();
        for (Submission submission : getSubmissions(handle).result()) {
            if (submission.contestId() != null) {
                contests.add(submission.contestId());
            }
        }
        return contests;
    }

    private Set<Integer> withoutCacheMarker(Set<Integer> cached) {
        Set<Integer> contests = new HashSet<>(cached);
        contests.remove(0);
        return contests;
    }

    @PostConstruct
    public void initContestList() {
        Map<Division, List<Contest>> loaded = new EnumMap<>(Division.class);
        for (Division division : Division.values()) {
            loaded.put(division, readContestList(division.fileName));
        }
        contestLists = Map.copyOf(loaded);
        refreshContestList();
    }

    /** 하나의 목록 버전에서 선택한 division을 복사한다. 반환 목록의 변경은 원본에 영향을 주지 않는다. */
    public List<Contest> getContestCandidates(int difficultyMask) {
        Map<Division, List<Contest>> snapshot = contestLists;
        List<Contest> candidates = new ArrayList<>();
        for (Division division : Division.values()) {
            if ((difficultyMask & division.mask) != 0) {
                candidates.addAll(snapshot.getOrDefault(division, List.of()));
            }
        }
        return candidates;
    }

    /** 시작 시와 매일 한국 시간 정오에 조회하며, 갱신 실패 시 기존 목록으로 계속 서비스한다. */
    @Scheduled(cron = "0 0 12 * * *", zone = "Asia/Seoul")
    public synchronized void refreshContestList() {
        try {
            CodeforcesResponse<List<Contest>> response = restClient.get()
                    .uri("https://codeforces.com/api/contest.list?gym=false")
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            List<Contest> finished = requireContestList(response).stream()
                    .filter(contest -> "FINISHED".equals(contest.phase()))
                    .toList();
            long currentCount = contestLists.values().stream().mapToLong(List::size).sum();
            if (finished.size() < currentCount) {
                throw new IllegalArgumentException("완료 대회 수가 기존 목록보다 적습니다. 기존: %d, 조회: %d"
                        .formatted(currentCount, finished.size()));
            }
            if (currentCount == finished.size()) {
                return;
            }

            Set<Integer> knownIds = contestLists.values().stream().flatMap(List::stream)
                    .map(Contest::id).collect(Collectors.toSet());
            Map<Division, List<Contest>> updated = new EnumMap<>(Division.class);
            for (Division division : Division.values()) {
                updated.put(division, new ArrayList<>(contestLists.getOrDefault(division, List.of())));
            }
            int addedCount = 0;
            for (Contest contest : finished) {
                if (knownIds.add(contest.id())) {
                    updated.get(classify(contest.name())).add(contest);
                    addedCount++;
                }
            }
            if (addedCount == 0) {
                return;
            }
            updated.replaceAll((division, contests) -> List.copyOf(contests));
            writeContestLists(updated);
            contestLists = Map.copyOf(updated);
            log.info("새로 종료된 대회 {}개를 기존 목록에 추가했습니다.", addedCount);
        } catch (RestClientException | IOException | JacksonException | IllegalArgumentException exception) {
            log.warn("대회 목록을 갱신하지 못해 기존 목록을 유지합니다.", exception);
        }
    }

    private Division classify(String name) {
        Set<String> divisions = DIVISION_PATTERN.matcher(name).results()
                .map(match -> match.group(1)).collect(Collectors.toSet());
        if (divisions.contains("2") && name.toLowerCase(Locale.ROOT).contains("educational")) {
            return Division.EDUCATIONAL;
        }
        if (divisions.contains("1") && divisions.contains("2")) {
            return Division.DIV12;
        }
        if (divisions.contains("1")) {
            return Division.DIV1;
        }
        if (divisions.contains("2")) {
            return Division.DIV2;
        }
        if (divisions.contains("3")) {
            return Division.DIV3;
        }
        if (divisions.contains("4")) {
            return Division.DIV4;
        }
        return Division.OTHER;
    }

    private List<Contest> requireContestList(CodeforcesResponse<List<Contest>> response) {
        if (response == null || !"OK".equals(response.status()) || response.result() == null
                || response.result().stream().anyMatch(contest -> contest == null || contest.id() == null
                || contest.id() <= 0 || contest.name() == null || contest.name().isBlank()
                || contest.phase() == null)) {
            throw new IllegalArgumentException("대회 목록 응답이 올바르지 않습니다.");
        }
        return List.copyOf(response.result());
    }

    private List<Contest> readContestList(String fileName) {
        Path divisionFile = divisionsDirectory.resolve(fileName);
        try (InputStream inputStream = Files.newInputStream(divisionFile)) {
            return requireContestList(jsonMapper.readValue(inputStream,
                    new TypeReference<CodeforcesResponse<List<Contest>>>() {
                    }));
        } catch (IOException | JacksonException | IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorMessage.CONTEST_DATA_LOAD_FAILED, "대회 목록 파일 " + divisionFile + "을 불러오지 못했습니다.", exception);
        }
    }

    private void writeContestLists(Map<Division, List<Contest>> updated) throws IOException {
        Path staging = Files.createTempDirectory(divisionsDirectory, ".refresh-");
        List<Division> replaced = new ArrayList<>();
        boolean keepBackup = false;
        try {
            for (Division division : Division.values()) {
                jsonMapper.writeValue(staging.resolve(division.fileName).toFile(),
                        new CodeforcesResponse<>("OK", null, updated.get(division)));
                Files.copy(divisionsDirectory.resolve(division.fileName),
                        staging.resolve(division.fileName + ".bak"));
            }
            try {
                for (Division division : Division.values()) {
                    Files.move(staging.resolve(division.fileName), divisionsDirectory.resolve(division.fileName),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    replaced.add(division);
                }
            } catch (IOException failure) {
                for (Division division : replaced) {
                    try {
                        Files.move(staging.resolve(division.fileName + ".bak"),
                                divisionsDirectory.resolve(division.fileName),
                                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException rollbackFailure) {
                        keepBackup = true;
                        failure.addSuppressed(rollbackFailure);
                    }
                }
                throw failure;
            }
        } finally {
            if (keepBackup) {
                log.error("대회 파일 복구에 실패해 원본 백업을 {}에 보존합니다.", staging);
            } else {
                try (var files = Files.list(staging)) {
                    for (Path file : files.toList()) {
                        Files.delete(file);
                    }
                    Files.delete(staging);
                } catch (IOException cleanupFailure) {
                    log.warn("대회 갱신 임시 디렉터리를 정리하지 못했습니다: {}", staging, cleanupFailure);
                }
            }
        }
    }

    private <T> CodeforcesResponse<T> requireSuccessfulResponse(CodeforcesResponse<T> response, String handle) {
        if (response == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorMessage.CODEFORCES_API_FAILED,
                    "Codeforces API 응답이 없습니다.");
        }
        if ("OK".equals(response.status()) && response.result() != null) {
            return response;
        }
        if ("FAILED".equals(response.status()) && response.comment() != null
                && response.comment().toLowerCase(Locale.ROOT).contains("not found")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorMessage.CODEFORCES_USER_NOT_FOUND,
                    "Codeforces 사용자 " + handle + "을 찾을 수 없습니다.");
        }
        if ("FAILED".equals(response.status())) {
            String detail = response.comment() == null
                    ? "Codeforces API 요청에 실패했습니다."
                    : "Codeforces API 요청에 실패했습니다: " + response.comment();
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorMessage.CODEFORCES_API_FAILED, detail);
        }
        throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorMessage.CODEFORCES_API_FAILED,
                "Codeforces API 응답이 올바르지 않습니다.");
    }

}
