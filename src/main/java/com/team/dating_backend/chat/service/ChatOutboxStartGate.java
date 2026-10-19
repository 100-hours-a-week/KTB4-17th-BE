package com.team.dating_backend.chat.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 배포 스크립트의 전환 전 데이터 동기화가 끝날 때까지 발행을 막는다.
 */
@Component
public class ChatOutboxStartGate {

    private final Path releaseFile;
    private final String instanceId;
    private final AtomicBoolean opened;

    public ChatOutboxStartGate(
        @Value("${app.chat.outbox.start-gate-file:}") String releaseFile,
        @Value("${HOSTNAME:local}") String instanceId, MeterRegistry registry) {
        this.releaseFile = releaseFile.isBlank() ? null : Path.of(releaseFile);
        this.instanceId = instanceId;
        this.opened = new AtomicBoolean(this.releaseFile == null);
        Gauge.builder("chat.outbox.start.gate.open", opened, value -> value.get() ? 1 : 0)
            .description("Publisher startup gate: one only after pre-cutover synchronization")
            .register(registry);
    }

    public boolean isOpen() {
        if (opened.get()) {
            return true;
        }
        try {
            // 이전 컨테이너의 표시 파일로 새 컨테이너의 발행을 허용하지 않는다.
            if (Files.readString(releaseFile).strip().equals(instanceId)) {
                opened.set(true);
            }
        } catch (IOException exception) {
            // 표시 파일이 없거나 읽을 수 없으면 발행을 막는다.
        }
        return opened.get();
    }
}
