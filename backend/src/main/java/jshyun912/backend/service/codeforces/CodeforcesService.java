package jshyun912.backend.service.codeforces;

import jakarta.annotation.PostConstruct;
import jshyun912.backend.dto.codeforces.CodeforcesResponse;
import jshyun912.backend.dto.codeforces.Contest;
import jshyun912.backend.dto.codeforces.Submission;
import jshyun912.backend.exception.ApiException;
import jshyun912.backend.exception.ErrorMessage;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.core.type.TypeReference;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Service
@Slf4j
@RequiredArgsConstructor
public class CodeforcesService {
    private static final Path DEFAULT_DIVISIONS_DIRECTORY = Path.of("data", "divisions");

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    @Value("${app.divisions-directory:data/divisions}")
    @Setter(AccessLevel.PACKAGE)
    private Path divisionsDirectory = DEFAULT_DIVISIONS_DIRECTORY;

    public List<Contest> div1ContestList = new ArrayList<>();
    public List<Contest> div12ContestList = new ArrayList<>();
    public List<Contest> div2ContestList = new ArrayList<>();
    public List<Contest> div2EduContestList = new ArrayList<>();
    public List<Contest> div3ContestList = new ArrayList<>();
    public List<Contest> div4ContestList = new ArrayList<>();
    public List<Contest> otherContestList = new ArrayList<>();

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
        Set<Integer> contests = new HashSet<>();

        List<Submission> submissions = getSubmissions(handle).result();

        for (Submission submission : submissions) {
            contests.add(submission.contestId());
        }

        return contests;
    }

    @PostConstruct
    public void initContestList() {
        div1ContestList.addAll(readContestList("Div1.json"));
        div12ContestList.addAll(readContestList("Div1Plus2.json"));
        div2ContestList.addAll(readContestList("Div2.json"));
        div2EduContestList.addAll(readContestList("Div2Educational.json"));
        div3ContestList.addAll(readContestList("Div3.json"));
        div4ContestList.addAll(readContestList("Div4.json"));
        otherContestList.addAll(readContestList("others.json"));

    }

    private List<Contest> readContestList(String fileName) {
        Path divisionFile = divisionsDirectory.resolve(fileName);

        try {
            try (InputStream inputStream = Files.newInputStream(divisionFile)) {
                return jsonMapper.readValue(inputStream, new TypeReference<CodeforcesResponse<List<Contest>>>() {
                }).result();
            }
        } catch (IOException | JacksonException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorMessage.CONTEST_DATA_LOAD_FAILED, "대회 목록 파일 " + divisionFile + "을 불러오지 못했습니다.", exception);
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
