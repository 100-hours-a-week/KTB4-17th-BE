package com.team.dating_backend.aisimulation;

import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads;
import com.team.dating_backend.aisimulation.dto.response.AiSimulationResponses;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

public final class AiSimulationTestFixtures {

    public static final Long USER_ID = 7L;
    public static final Long TARGET_ID = 8L;
    public static final Long SESSION_ID = 31L;
    public static final String AI_SIMULATION_ID = "sim-001";
    public static final OffsetDateTime AI_CREATED_AT = OffsetDateTime.of(
        2026, 9, 28, 1, 2, 3, 0, ZoneOffset.UTC);
    public static final LocalDateTime CREATED_AT = AI_CREATED_AT.toLocalDateTime();

    private AiSimulationTestFixtures() {}

    public static AiSimulationAiPayloads.SimulationResponse aiResponse() {
        AiSimulationAiPayloads.PersonaBrief me = new AiSimulationAiPayloads.PersonaBrief(
            "persona-a", USER_ID.toString(), "나", 1, "차분한 사람", 90);
        AiSimulationAiPayloads.PersonaBrief partner = new AiSimulationAiPayloads.PersonaBrief(
            "persona-b", TARGET_ID.toString(), "상대", 2, "따뜻한 사람", 85);
        List<AiSimulationAiPayloads.Turn> transcript = IntStream.range(0, 20)
            .mapToObj(index -> new AiSimulationAiPayloads.Turn(
                index,
                index % 2 == 0 ? "a" : "b",
                "대화 " + index))
            .toList();
        return new AiSimulationAiPayloads.SimulationResponse(
            AI_SIMULATION_ID,
            me,
            partner,
            10,
            transcript,
            report(),
            AI_CREATED_AT);
    }

    public static AiSimulationAiPayloads.MatchingReport report() {
        List<AiSimulationAiPayloads.Area> areas = List.of(
            area("intimacy"),
            area("communication"),
            area("conflict"),
            area("ideal"),
            area("orientation"));
        return new AiSimulationAiPayloads.MatchingReport(
            AI_SIMULATION_ID,
            "persona-a",
            "persona-b",
            new AiSimulationAiPayloads.Overall(
                78, "GOOD", "잘 맞아요", "편안한 두 사람", "대화가 잘 이어져요."),
            areas,
            List.of(new AiSimulationAiPayloads.Highlight(
                "click", 2, "좋아하는 게 비슷하네요", "공통 관심사를 찾았어요.")),
            List.of("연락 리듬이 비슷해요."),
            List.of("갈등 때 잠시 쉬어가세요."),
            List.of(),
            new AiSimulationAiPayloads.DateSuggestion(
                List.of("산책"), List.of("시끄러운 곳"), "조용한 장소가 좋아요."),
            new AiSimulationAiPayloads.Confidence(
                85, List.of(), "충분한 정보가 있어요."),
            "llm",
            AI_CREATED_AT);
    }

    public static AiSimulationResponses.Detail publicDetail() {
        AiSimulationResponseMapperForTest mapper = new AiSimulationResponseMapperForTest();
        return mapper.detail();
    }

    private static AiSimulationAiPayloads.Area area(String name) {
        return new AiSimulationAiPayloads.Area(
            name,
            name + " label",
            70,
            "GOOD",
            "잘 맞아요",
            "영역 설명",
            List.of(new AiSimulationAiPayloads.Dimension(
                name + "_dimension",
                "차원",
                70,
                75,
                "similar",
                95,
                "비슷해요.",
                "HIGH")));
    }

    private static final class AiSimulationResponseMapperForTest {

        private AiSimulationResponses.Detail detail() {
            return new com.team.dating_backend.aisimulation.service.AiSimulationResponseMapper()
                .toDetail(SESSION_ID, CREATED_AT, aiResponse(), aiResponse().transcript());
        }
    }
}
