package com.team.dating_backend.chat.api;

public interface ChatRoomParticipantRegistrar {

    void registerParticipants(
        long chatRoomId,
        long senderId,
        long receiverId);
}
