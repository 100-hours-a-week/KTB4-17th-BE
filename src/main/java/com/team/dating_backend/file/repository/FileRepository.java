package com.team.dating_backend.file.repository;

import com.team.dating_backend.file.entity.File;
import java.util.Optional;

public interface FileRepository {

    File save(File file);

    Optional<File> findActiveById(Long fileId);
}
