package com.aistudy.server.operations;

import com.aistudy.server.storage.LocalStorageService;

import java.nio.file.Path;

/**
 * BUSINESS-025 — package-local operations seam for {@link LocalStorageService}.
 *
 * <p>Exposes only the metadata required for health/reconciliation, without
 * exposing the full {@link LocalStorageService} API surface.
 */
public interface LocalStorageOperations {

    Path getRoot();
}
