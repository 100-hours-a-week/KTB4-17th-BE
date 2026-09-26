package com.team.dating_backend.file.support;

import com.team.dating_backend.file.entity.File;
import com.team.dating_backend.file.repository.FileRepository;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class FakeFileRepository implements FileRepository {

    private final Map<Long, File> files = new HashMap<>();
    private long nextId = 1L;
    private File savedFile;
    private int saveCount;
    private RuntimeException nextSaveException;

    @Override
    public File save(File file) {
        return persist(file);
    }

    @Override
    public File saveAndFlush(File file) {
        return persist(file);
    }

    @Override
    public Optional<File> findActiveById(Long fileId) {
        return Optional.ofNullable(files.get(fileId)).filter(file -> !file.isDeleted());
    }

    @Override
    public Optional<File> findActiveByIdAndOwner(Long fileId, Long ownerUserId) {
        return findActiveById(fileId).filter(file -> file.getOwnerUserId().equals(ownerUserId));
    }

    public File savedFile() {
        return savedFile;
    }

    public int saveCount() {
        return saveCount;
    }

    public void failNextSave() {
        nextSaveException = new RuntimeException("DB 저장 실패");
    }

    private File persist(File file) {
        if (nextSaveException != null) {
            RuntimeException exception = nextSaveException;
            nextSaveException = null;
            throw exception;
        }

        if (file.getId() == null) {
            assignId(file, nextId++);
        }

        files.put(file.getId(), file);
        savedFile = file;
        saveCount++;
        return file;
    }

    private void assignId(File file, Long id) {
        try {
            Field idField = File.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(file, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Fake Repository가 파일 ID를 할당하지 못했습니다.", exception);
        }
    }
}
