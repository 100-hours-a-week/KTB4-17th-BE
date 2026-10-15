package com.team.dating_backend.profiletrust.dto;

import java.util.List;

public record PrimaryPhotoCheckResult(boolean passed, List<String> reasonCodes) {}
