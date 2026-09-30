-- =============================================================================
-- Migration v01 — initial vault schema.
--
-- Unlike the main backend, the vault is NOT multi-tenant — it has exactly one
-- database/schema, whichever the connecting JDBC URL's currentSchema (or the
-- role's default search_path) points at. No {{SCHEMA_NAME}} templating needed:
-- just run this once against the vault's own database.
--
-- HOW TO USE (local dev):
--   psql -d postgres -c "CREATE SCHEMA IF NOT EXISTS vault;"
--   psql -d postgres -c "SET search_path TO vault;" -f migration_v01_init.sql
-- =============================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS appointment_registration (
    id               UUID PRIMARY KEY,
    clinic_uuid      UUID NOT NULL,
    appointment_id   INTEGER NOT NULL,
    appointment_date TIMESTAMPTZ NOT NULL,
    delete_after     TIMESTAMPTZ NOT NULL,
    status           VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_appointment_registration_clinic_appointment UNIQUE (clinic_uuid, appointment_id)
);

CREATE INDEX IF NOT EXISTS idx_appointment_registration_delete_after
    ON appointment_registration (delete_after);

CREATE TABLE IF NOT EXISTS uploaded_file (
    id                UUID PRIMARY KEY,
    registration_id   UUID NOT NULL REFERENCES appointment_registration (id),
    original_filename TEXT NOT NULL,
    stored_path       TEXT NOT NULL UNIQUE,
    mime_type         TEXT,
    size_bytes        BIGINT NOT NULL,
    clam_scan_result  VARCHAR(16) NOT NULL,
    uploaded_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_uploaded_file_registration_id
    ON uploaded_file (registration_id);

COMMENT ON TABLE appointment_registration IS
    'One row per appointment SuperMed has registered with the vault. Identity is (clinic_uuid, appointment_id) together — appointment ids repeat across clinics.';
COMMENT ON TABLE uploaded_file IS
    'A single uploaded document. No patient name/email/phone column anywhere — data minimization is structural.';

COMMIT;
