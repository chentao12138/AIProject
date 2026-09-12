package com.aistudy.server.operations;

import com.aistudy.server.config.properties.OperationsProperties;
import com.aistudy.server.source.asset.mapper.SourceAssetMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * BUSINESS-025 — admin-only operational maintenance endpoints.
 */
@RestController
@RequestMapping("/api/v1/admin/operations")
public class AdminOperationsController {

    private static final Logger log = LoggerFactory.getLogger(AdminOperationsController.class);

    private final StorageReconciliationService storageReconciliationService;

    public AdminOperationsController(StorageReconciliationService storageReconciliationService) {
        this.storageReconciliationService = storageReconciliationService;
    }

    public enum ReconcileMode {
        DRY_RUN,
        CLEANUP
    }

    public record ReconcileRequest(ReconcileMode mode) {
    }

    @PostMapping("/storage/reconcile")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<StorageReconciliationResult> reconcileStorage(@RequestBody(required = false) ReconcileRequest request) {
        boolean cleanup = request != null && request.mode() == ReconcileMode.CLEANUP;
        StorageReconciliationResult result = storageReconciliationService.reconcile(cleanup);
        return ResponseEntity.ok(result);
    }
}
