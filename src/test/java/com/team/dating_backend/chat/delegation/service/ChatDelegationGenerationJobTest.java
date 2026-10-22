package com.team.dating_backend.chat.delegation.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.team.dating_backend.chat.delegation.enums.ChatDelegationMessageStatus;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationMessageRepository;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

class ChatDelegationGenerationJobTest {

    @Test
    void 전송_대기_중인_답장을_우선_처리한다() {
        ChatDelegationRepository delegationRepository = mock(ChatDelegationRepository.class);
        ChatDelegationMessageRepository messageRepository = mock(
            ChatDelegationMessageRepository.class);
        ChatDelegationGenerationWorker worker = mock(ChatDelegationGenerationWorker.class);
        ChatDelegationGenerationJob job = new ChatDelegationGenerationJob(
            delegationRepository, messageRepository, worker);
        when(messageRepository.findIdsByStatus(
            eq(ChatDelegationMessageStatus.READY), any(Limit.class)))
            .thenReturn(List.of(30L, 31L));

        job.generateDueMessages();

        verify(worker).deliver(30L);
        verify(worker).deliver(31L);
        verify(worker, never()).recover(any());
        verify(worker, never()).retry(any());
        verify(worker, never()).generate(any());
        verifyNoInteractions(delegationRepository);
    }
}
