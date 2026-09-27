package com.team.dating_backend.profile.dto;

public record ProfileImageAccessResult(
    Long fileId,
    short displayOrder,
    String imageUrl) {}
