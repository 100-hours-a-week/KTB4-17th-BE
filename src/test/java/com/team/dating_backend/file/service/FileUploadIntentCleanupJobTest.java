package com.team.dating_backend.file.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.file.config.FileProperties;
import com.team.dating_backend.file.entity.FileUploadIntent;
import com.team.dating_backend.file.enums.FileUploadIntentStatus;
import com.team.dating_backend.file.support.FakeFileRepository;
import com.team.dating_backend.file.support.FakeFileStorage;
import com.team.dating_backend.file.support.FakeFileUploadIntentRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class FileUploadIntentCleanupJobTest {

    @Test
    void 만료_완료된_intent에서는_임시객체만_삭제한다() {
        FakeFileStorage storage = new FakeFileStorage();
        FakeFileUploadIntentRepository repository = new FakeFileUploadIntentRepository(
            new FakeFileRepository());
        String stagingKey = "staging/cleanup-test";
        String finalKey = "files/cleanup-test";
        storage.createUploadUrl(stagingKey, "image/png", Duration.ofMinutes(1));
        storage.addUploadedObject("image/png", new byte[]{1, 2, 3});
        storage.promote(stagingKey, finalKey, "image/png", "etag-1");

        FileUploadIntent intent = FileUploadIntent.create(
            1L,
            stagingKey,
            finalKey,
            "test.png",
            "image/png",
            LocalDateTime.now().minusHours(1));
        intent.markCompleted(101L);
        FileUploadIntent savedIntent = repository.saveAndFlush(intent);

        FileProperties properties = new FileProperties();
        properties.setCleanupBatchSize(50);
        properties.setUploadIntentRetention(Duration.ofDays(30));
        FileUploadIntentCleanupJob job = new FileUploadIntentCleanupJob(storage, repository, properties);

        job.cleanExpiredObjects();

        assertThat(storage.inspectUploadedObject(stagingKey)).isEmpty();
        assertThat(storage.inspectUploadedObject(finalKey)).isPresent();
        assertThat(savedIntent.getStatus()).isEqualTo(FileUploadIntentStatus.COMPLETED);
        assertThat(savedIntent.getStagingCleanedAt()).isNotNull();
    }

    @Test
    void 만료된_미완료_intent에서는_임시객체와_고아_최종객체를_정리한다() {
        FakeFileStorage storage = new FakeFileStorage();
        FakeFileUploadIntentRepository repository = new FakeFileUploadIntentRepository(
            new FakeFileRepository());
        String stagingKey = "staging/expired-test";
        String finalKey = "files/orphan-test";
        storage.createUploadUrl(stagingKey, "image/png", Duration.ofMinutes(1));
        storage.addUploadedObject("image/png", new byte[]{1, 2, 3});
        storage.promote(stagingKey, finalKey, "image/png", "etag-1");

        FileUploadIntent intent = FileUploadIntent.create(
            1L,
            stagingKey,
            finalKey,
            "test.png",
            "image/png",
            LocalDateTime.now().minusHours(1));
        FileUploadIntent savedIntent = repository.saveAndFlush(intent);

        FileProperties properties = new FileProperties();
        properties.setCleanupBatchSize(50);
        properties.setUploadIntentRetention(Duration.ofDays(30));
        FileUploadIntentCleanupJob job = new FileUploadIntentCleanupJob(storage, repository, properties);

        job.cleanExpiredObjects();

        assertThat(storage.inspectUploadedObject(stagingKey)).isEmpty();
        assertThat(storage.inspectUploadedObject(finalKey)).isEmpty();
        assertThat(savedIntent.getStatus()).isEqualTo(FileUploadIntentStatus.EXPIRED);
        assertThat(savedIntent.getStagingCleanedAt()).isNotNull();
    }
}
