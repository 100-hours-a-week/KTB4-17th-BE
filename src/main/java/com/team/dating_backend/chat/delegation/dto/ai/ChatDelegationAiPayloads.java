package com.team.dating_backend.chat.delegation.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationEndType;
import java.util.List;
import java.util.UUID;

public final class ChatDelegationAiPayloads {

    private ChatDelegationAiPayloads() {}

    public enum Speaker {
        REQUESTER,
        TARGET
    }

    public record RecentMessage(
        Speaker speaker,
        String content) {}

    public record DraftRequest(
        @JsonProperty("request_id") UUID requestId,
        @JsonProperty("room_id") Long roomId,
        @JsonProperty("delegation_id") Long delegationId,
        @JsonProperty("requester_user_id") String requesterUserId,
        @JsonProperty("end_type") ChatDelegationEndType endType,
        @JsonProperty("recent_messages") List<RecentMessage> recentMessages) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DraftResponse(
        @JsonProperty("room_id") Long roomId,
        @JsonProperty("draft_id") String draftId,
        String status,
        List<Turn> turns) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfirmResponse(
        @JsonProperty("draft_id") String draftId,
        @JsonProperty("delegation_id") Long delegationId,
        String status) {}

    public record MessageRequest(
        @JsonProperty("request_id") UUID requestId,
        @JsonProperty("room_id") Long roomId,
        @JsonProperty("delegation_id") Long delegationId,
        @JsonProperty("user_id") String userId,
        @JsonProperty("target_user_id") String targetUserId,
        @JsonProperty("end_type") ChatDelegationEndType endType,
        @JsonProperty("recent_messages") List<RecentMessage> recentMessages) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessageResponse(
        @JsonProperty("room_id") Long roomId,
        @JsonProperty("message_id") Long messageId,
        @JsonProperty("ai_response") String aiResponse,
        String status,
        @JsonProperty("ai_turn_count") Integer aiTurnCount,
        @JsonProperty("is_final") Boolean isFinal,
        @JsonProperty("end_turns") Integer endTurns) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Turn(Integer turn, String message) {}
}
