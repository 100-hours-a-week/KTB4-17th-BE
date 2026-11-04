package com.team.dating_backend.profile.repository;

public record PopularProfileRow(
    Long userId,
    String nickname,
    String provinceName,
    String regionName,
    Long likeCount) {}
