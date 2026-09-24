-- V051__create_folder_import.sql
-- Final Backend Feature Freeze — FolderSync protocol.

CREATE TABLE folder_import_snapshot (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    space_id        BIGINT        NOT NULL,
    source_id       BIGINT        NOT NULL,
    total_files     INT           NOT NULL DEFAULT 0,
    unchanged_files INT           NOT NULL DEFAULT 0,
    added_files     INT           NOT NULL DEFAULT 0,
    removed_files   INT           NOT NULL DEFAULT 0,
    status          VARCHAR(32)   NOT NULL DEFAULT 'DRAFT',
    created_at      DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),

    KEY idx_folder_snapshot_space_source (space_id, source_id, created_at),

    CONSTRAINT fk_folder_snapshot_space FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_folder_snapshot_source FOREIGN KEY (source_id) REFERENCES source (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'FolderImportSnapshot: one folder sync manifest.';

CREATE TABLE folder_import_entry (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    snapshot_id      BIGINT      NOT NULL,
    relative_path    VARCHAR(512) NOT NULL,
    sha256           VARCHAR(64)  NULL,
    status           VARCHAR(32)  NOT NULL,
    source_asset_id  BIGINT       NULL,
    created_at       DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),

    UNIQUE KEY uq_folder_entry_snapshot_path (snapshot_id, relative_path),
    KEY idx_folder_entry_snapshot (snapshot_id),
    KEY idx_folder_entry_asset (source_asset_id),

    CONSTRAINT fk_folder_entry_snapshot FOREIGN KEY (snapshot_id) REFERENCES folder_import_snapshot (id),
    CONSTRAINT fk_folder_entry_asset FOREIGN KEY (source_asset_id) REFERENCES source_asset (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'FolderImportEntry: one file entry inside a FolderImportSnapshot.';
