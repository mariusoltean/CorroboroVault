# CorroboroVault — Claude Context

## Project Overview

Standalone Spring Boot service that stores files patients upload related to an
appointment (lab results, referral letters, imaging) for SuperMed/Corroboro.
It is a genuinely separate service from `CorroboroOnlineProgrammerBackend`
("cop-be", "the main backend"), not a module inside it — see
`~/Dev/CorroboroOnlineProgrammerBackend/File-Storage.md` for the full design
rationale this repo implements.

- **Java**: 21
- **Spring Boot**: 3.2.4
- **Build**: Maven (`pom.xml`), packaged as an executable **jar** (unlike the
  main backend's WAR/Tomcat setup — this is a fresh Docker-deployed
  microservice with embedded Tomcat, no external servlet container).
- **Server port**: `8090` (dev default; `VAULT_SERVER_PORT` in prod)

The vault has **no public exposure in any real deployment** — it's reached
only over the internal Docker network by the main backend. Patients and
doctors never talk to it directly; SuperMed's own backend proxies every
call. See File-Storage.md's "Decisions made with the user" for why.

---

## Architecture

Mirrors the main backend's hexagonal/ports-and-adapters style:

```
ro.corroboro.vault
├── VaultApplication.java
├── config/
│   ├── SecurityConfig.java            # one filter, no form login
│   ├── ServiceTokenAuthFilter.java    # the vault's only auth mechanism
│   └── GlobalExceptionHandler.java
├── database/
│   ├── entity/          # AppointmentRegistration, UploadedFile, enums
│   └── repository/
├── core/
│   ├── domain/{request,response}/     # *RequestModelV1 / *ResponseModelV1
│   └── services/
│       ├── registration/  # RegistrationService(+Impl)
│       ├── storage/       # FileStorageService(+Impl) — local disk read/write
│       ├── upload/        # UploadService(+Impl) — scan, store, list, zip
│       ├── clamav/        # ClamAvClient(+Impl) — INSTREAM over TCP
│       └── purge/         # PurgeScheduler — daily retention sweep
└── ports/incoming/rest/
    └── InternalController.java  # the entire API surface, service-token-gated
```

**No multi-tenancy** — unlike the main backend, this is one service with one
database. Identity is always the compound pair `(clinicUuid, appointmentId)`,
never `appointmentId` alone: appointment ids are per-clinic-schema
auto-increment values in the main backend, so the same numeric id exists in
every clinic for a completely different appointment.

**Data minimization is structural**: no patient name/email/phone column
exists anywhere in this schema. The vault only ever knows
`(clinicUuid, appointmentId)` plus the files themselves.

---

## Auth

One mechanism, one caller: a static bearer secret (`vault.service-token`,
`VAULT_SERVICE_TOKEN` in the environment) that must match the same value
configured in the main backend's own environment. `ServiceTokenAuthFilter`
checks every request except `/actuator/health`. There is no second
(patient-facing) token type — patients never address the vault directly.

---

## REST API

Base path: `/internal` — every endpoint requires `Authorization: Bearer <VAULT_SERVICE_TOKEN>`.

| Method & Path | Purpose |
|---|---|
| `POST /internal/registrations` | Register (or idempotently update) an appointment. Body: `{clinicUuid, appointmentId, appointmentDate}`. Computes `deleteAfter = appointmentDate + retention.days`. |
| `PUT /internal/registrations/{clinicUuid}/{appointmentId}` | Reschedule: updates `appointmentDate`/`deleteAfter` only. |
| `DELETE /internal/registrations/{clinicUuid}/{appointmentId}` | Cancel: sets `status=CANCELLED`, blocking further uploads immediately. Does **not** touch `deleteAfter` — the original clock keeps running. |
| `POST /internal/registrations/{clinicUuid}/{appointmentId}/files` | Upload (multipart, field name `file`). Scans via ClamAV first; `409` if the registration is cancelled or missing, `422` if infected. |
| `GET /internal/registrations/{clinicUuid}/{appointmentId}/files` | List files — allowed regardless of registration status (a cancelled appointment's files stay visible to the doctor). |
| `GET /internal/registrations/{clinicUuid}/{appointmentId}/files/{fileId}` | Single-file download. Range-aware (`206 Partial Content`) for pause/resume on large files. |
| `GET /internal/registrations/{clinicUuid}/{appointmentId}/files/zip` | All files as one on-the-fly zip. **Not** Range-resumable by design — see File-Storage.md's "Bulk download: zip-all" note. |
| `DELETE /internal/registrations/{clinicUuid}/{appointmentId}/files/{fileId}` | Delete a single file. |
| `POST /internal/admin/purge` | Manually trigger the retention sweep. Only responds when `vault.purge.manual-trigger-enabled=true` (dev/acceptance) — `404`s otherwise, to avoid hinting the endpoint exists in prod. |

**DTO naming**: `*RequestModelV1` / `*ResponseModelV1`, matching the main
backend's convention.

---

## Configuration Profiles

All tunables are properties, driven by environment variables with sane
defaults — nothing is hardcoded, following the main backend's
`${ENV_VAR:default}` convention.

| File | Profile | Notes |
|------|---------|-------|
| `application.properties` | default / production | Real defaults, everything overridable via env vars |
| `application-dev.properties` | `dev` | Local Postgres (shared instance, own `vault` schema), ClamAV disabled, manual purge trigger enabled |
| `application-acceptance.properties` | `acceptance` | Own full vault instance; ClamAV disabled (synthetic data only) |

Run with a profile: `mvn spring-boot:run -Dspring-boot.run.profiles=dev`

**Key properties** (see `application.properties` for the full list with
comments):

- `vault.service-token` — the shared secret with the main backend
- `vault.storage.root-path` — on-disk file storage root
- `vault.retention.days` (default 15), `vault.purge.enabled`, `vault.purge.cron`
- `vault.clamav.enabled`, `.host`, `.port`, `.timeout-ms`
- `spring.servlet.multipart.max-file-size`/`max-request-size` — raised to
  2GB by default (medical imaging, not just small documents)

**Local dev prerequisite** — create the schema once:
```bash
psql -d postgres -c "CREATE SCHEMA IF NOT EXISTS vault;"
psql -d postgres -c "SET search_path TO vault;" -f src/main/resources/database/migration_v01_init.sql
```

---

## Database Schema

Manual SQL migrations, same convention as the main backend (no Flyway) —
see `src/main/resources/database/`. `spring.jpa.hibernate.ddl-auto=validate`:
the schema must already exist before the app starts.

| Table | Key Columns |
|-------|------------|
| `appointment_registration` | `id` (UUID), `clinic_uuid`, `appointment_id` (unique together), `appointment_date`, `delete_after`, `status` (`ACTIVE`/`CANCELLED`) |
| `uploaded_file` | `id` (UUID), `registration_id` (FK), `original_filename`, `stored_path` (`<clinicUuid>/<appointmentId>/<fileUuid>.<ext>`), `mime_type`, `size_bytes`, `clam_scan_result` (`CLEAN`/`INFECTED`/`ERROR`), `uploaded_at` |

`UploadedFile.id` is **self-assigned** (not `@GeneratedValue`) — the service
needs the UUID before insert, to build `storedPath` from it.

ClamAV `ERROR` (scan itself failed, e.g. clamd briefly unreachable) still
stores the file — only a confirmed `INFECTED` verdict is rejected outright.

---

## Build & Run

```bash
mvn clean install

mvn spring-boot:run -Dspring-boot.run.profiles=dev

mvn clean package
java -jar target/corroboro-vault-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

---

## Not Yet Implemented (out of scope for this repo alone)

Per File-Storage.md, still needed elsewhere before this service is wired
into a real appointment flow:
- **Main backend integration**: `VaultClient`, lifecycle hooks (create/
  update/cancel), the patient-facing proxy endpoints, and the doctor
  dashboard's `kind=VAULT` file rows.
- **Deployment**: `docker-compose.yml`/`.acceptance.yml` entries for
  `vault-db`/`vault-clamav`/`vault-be`, with no gateway route.
- **NAS/backup path**: confirming what actually syncs
  `/Users/corroboroserver-mini/BackupSuperMed` off-box, and pointing
  `vault.storage.root-path` at a path that mechanism already covers.
