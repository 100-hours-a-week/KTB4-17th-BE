package com.team.dating_backend.file.controller;

import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.file.dto.CreateFileUploadIntentCommand;
import com.team.dating_backend.file.dto.FileAccessUrlResult;
import com.team.dating_backend.file.dto.FileMetadataResult;
import com.team.dating_backend.file.dto.FileUploadIntentResult;
import com.team.dating_backend.file.dto.request.CreateFileUploadIntentRequest;
import com.team.dating_backend.file.dto.response.FileAccessUrlResponse;
import com.team.dating_backend.file.dto.response.FileMetadataResponse;
import com.team.dating_backend.file.dto.response.FileUploadIntentResponse;
import com.team.dating_backend.file.service.FileService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/files")
public class FileController {

    private final FileService fileService;

    @PostMapping("/upload-intents")
    public ResponseEntity<SuccessResponse<FileUploadIntentResponse>> createUploadIntent(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @Valid @RequestBody CreateFileUploadIntentRequest request) {
        FileUploadIntentResult result = fileService.createUploadIntent(
            principal.userId(),
            new CreateFileUploadIntentCommand(request.originalName(), request.contentType()));

        return ResponseEntity.status(HttpStatus.CREATED)
            .body(SuccessResponse.of(
                "file_upload_intent_create_success",
                FileUploadIntentResponse.from(result)));
    }

    @PostMapping("/upload-intents/{uploadIntentId}/complete")
    public SuccessResponse<FileMetadataResponse> completeUpload(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long uploadIntentId) {
        FileMetadataResult result = fileService.completeUpload(principal.userId(), uploadIntentId);
        return SuccessResponse.of("file_upload_complete_success", FileMetadataResponse.from(result));
    }

    @GetMapping("/{fileId}/metadata")
    public SuccessResponse<FileMetadataResponse> getMetadata(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long fileId) {
        FileMetadataResult result = fileService.getMetadata(principal.userId(), fileId);
        return SuccessResponse.of("file_metadata_get_success", FileMetadataResponse.from(result));
    }

    @GetMapping("/{fileId}/access-url")
    public SuccessResponse<FileAccessUrlResponse> createAccessUrl(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long fileId,
        @RequestParam(defaultValue = "attachment") String disposition) {
        FileAccessUrlResult result = fileService.createAccessUrl(principal.userId(), fileId, disposition);
        return SuccessResponse.of("file_access_url_create_success", FileAccessUrlResponse.from(result));
    }

    @DeleteMapping("/{fileId}")
    public ResponseEntity<Void> softDelete(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long fileId) {
        fileService.softDelete(principal.userId(), fileId);
        return ResponseEntity.noContent().build();
    }
}
