# Continue: wire the vault into SuperMed/Corroboro

Paste this whole file as your first message in a new Claude Code session on this
server, from wherever you keep the three repos (or clone them first — see
"Repos you need" below).

---

## Repos you need

- `CorroboroOnlineProgrammerBackend` — the main Spring Boot backend
- `CorroboroOnlineProgrammerFrontend` — the Angular frontend
- `CorroboroVault` — the new file-storage service (see "push it" note below)
- `CorroboroDeployment` — docker-compose + deploy scripts

All four are normally cloned as sibling directories (`~/Dev/<repo>` on the
machine this was built on). If this server doesn't have them yet, clone the
first two and `CorroboroDeployment` from wherever your team hosts them, and
get `CorroboroVault` across per the note in "What's already done" below.

## Context

SuperMed/Corroboro needed a way for patients to upload files (lab results,
referral letters, imaging) related to an appointment, for the doctor to see.
The design is a **separate** standalone Spring Boot service — "the vault" —
rather than a module in the main backend, because it holds more sensitive
data than anything else in the system today. Full rationale, every decision,
and the exact API contract are written up in:

**`CorroboroOnlineProgrammerBackend/File-Storage.md`** — read this file
completely first. It is the master plan for this whole feature and everything
below assumes you've read it.

## What's already done

1. **The vault service itself** — a new repo, `CorroboroVault`, fully
   implemented and verified end-to-end (register, upload with ClamAV
   INSTREAM scanning, list, Range-based resumable single-file download,
   zip-all download, cancel, purge scheduler, the clinic+appointment
   compound-key isolation). Read **`CorroboroVault/CLAUDE.md`** for its exact
   package layout, REST API, and every configuration property — that file is
   the source of truth for how to call this service, not File-Storage.md's
   higher-level design prose.

   - **This repo is currently local-only** (`git init`, no remote configured).
     Push it to your git hosting (or otherwise copy it to this server) before
     you start — it isn't reachable by `git clone` from anywhere yet.
   - It runs locally today against a shared local Postgres in its own
     `vault` schema, with `VAULT_CLAMAV_ENABLED=false` (dev profile) so it
     needs no running `clamd`. Check `GET /internal/status` after starting it
     to confirm what's actually active — see `CLAUDE.md`'s "Auth" and REST
     API sections for the exact bearer-token header to send.

2. **A temporary, real-but-stubbed patient-facing endpoint already exists in
   the main backend** — `AppointmentDocumentsController`
   (`ports/outgoing/rest/`, in `CorroboroOnlineProgrammerBackend`), serving
   `GET/POST/DELETE /api/appointments/{clinicId}/{appointmentId}/documents`.
   It currently **persists nothing** — POST just echoes back the uploaded
   file's real filename/size/mimeType without storing it anywhere. This was
   built so the frontend could be built and tested before the vault existed.
   **A core part of the remaining work is replacing this stub's internals
   with real calls to the vault**, without changing its URL shape or
   response shape — the frontend already depends on exactly this contract
   and should need zero changes once the swap is done correctly.

3. **The frontend patient-facing upload widget already exists and works
   against that stub** — `src/app/shared/components/appointment-documents/`
   in `CorroboroOnlineProgrammerFrontend`, plus its
   `AppointmentDocumentsService` (`src/app/shared/services/`). It's wired
   into the patient's "manage my appointment" page
   (`update-appointment.component.html`). You should not need to touch this
   for the backend integration work — only if the real vault-backed response
   shape ends up genuinely different from the stub's (it shouldn't).

## What's NOT done yet — do these in order

### 1. Main backend: talk to the vault

New package `ro.corroboro.cop.communication.vault` in
`CorroboroOnlineProgrammerBackend`, alongside the existing
`communication.email`/`communication.twilio` packages:

- **`VaultClient`** — thin HTTP client wrapping the vault's `/internal/**`
  endpoints (see `CorroboroVault/CLAUDE.md`'s REST API table for the exact
  paths/methods) with the `VAULT_SERVICE_TOKEN` bearer header. Upload and
  download calls must **stream, not buffer** — files can be hundreds of MB
  to a couple GB (medical imaging). Reuse `RestClient`/`RestTemplate`
  patterns already used elsewhere in this backend if any exist; otherwise a
  plain `java.net.http.HttpClient` with streaming request/response bodies is
  fine.
- **`VaultRegistrationService`/`Impl`** — `registerFor(clinicUuid,
  appointment)`, `updateForReschedule(...)`, `revokeForCancellation(...)`.
  **Log-and-swallow on any vault-call failure** — matching the existing
  Telegram/doctor-notification pattern already used at the same hook points
  (wrapped in try/catch so a downstream outage never blocks the appointment
  action itself). Look at how `AppointmentDbFacadeImpl` already wraps its
  Telegram notification calls for the exact shape to copy.

### 2. Main backend: new table + lifecycle hooks

- New table `vault_registration` in the main backend's **per-tenant**
  schema: `id`, `appointment_id` (unique in this schema — combined with the
  schema's own clinic identity this satisfies the vault's
  clinic+appointment compound-key requirement), `vault_registration_id`,
  timestamps. New `VaultRegistrationRepository` with `findByAppointmentId`.
- **Check the current highest `migration_vNN_*.sql` file** in
  `CorroboroOnlineProgrammerBackend/src/main/resources/database/` before
  numbering this one — it was v72 as of this handoff, but that may have
  moved on. Follow the exact `{{SCHEMA_NAME}}` placeholder convention the
  existing migrations use (see v70/v71/v72 for the current house style: `SET
  search_path`, `BEGIN`/`COMMIT`, `ADD COLUMN IF NOT EXISTS`/`CREATE TABLE IF
  NOT EXISTS`).
- Wire the four lifecycle hook points File-Storage.md names:
  `AppointmentDbFacadeImpl.create()` → register with the vault;
  `.update()` / `AdminServiceImpl.updateAppointmentDetails()` → update the
  vault's stored appointment date on reschedule (the existing
  `visibleChange` check in the admin path is the natural hook);
  `.delete()` / `AdminServiceImpl.updateAppointmentStatus()` → revoke on
  cancel (the existing `!confirmed` branch is the natural hook).

### 3. Main backend: replace the documents stub with the real thing

Rework `AppointmentDocumentsController` (currently a stub — see "What's
already done" #2 above) so that:
- `POST .../documents` actually streams the multipart upload through
  `VaultClient` to the vault (registering with the vault first via
  `VaultRegistrationService` if this is the appointment's first upload —
  though in practice registration should already have happened at
  create-time per step 2 above).
- `GET .../documents` proxies the vault's list call.
- `DELETE .../documents/{id}` proxies the vault's delete call.

Keep the exact same request/response shape the frontend already expects
(check `AppointmentDocumentsService` in the frontend repo, or the stub
controller's current DTOs, for the exact contract) — this swap should be
invisible to the frontend.

### 4. Main backend: doctor-facing access

- Extend `PatientDocumentsQueryServiceImpl` to merge in vault rows
  (`kind=VAULT`) via `VaultClient.listFiles(...)`, alongside the existing
  `GENERATED`/`FORM` kinds.
- New `GET /api/admin/{clinicId}/appointments/{appointmentId}/vault-files/{fileId}`
  — single-file download, **must forward the `Range` header** and relay the
  vault's response back streaming (not buffered) — this is what makes
  pause/resume work end-to-end for the doctor's browser.
- New `GET /api/admin/{clinicId}/appointments/{appointmentId}/vault-files/zip`
  — proxies the vault's zip-all stream through. Not Range-aware, by design
  (see File-Storage.md's "Bulk download: zip-all" note for why).

### 5. Frontend: doctor-side file list

`patient-documents.component.ts`/`.html` (shared between the admin
dashboard and doctor portal, in `CorroboroOnlineProgrammerFrontend`) — add
`kind: 'VAULT'` to `typeOptions`/`typeLabel()`, a per-file download action
hitting the new single-file endpoint, and a "download all (.zip)" action
hitting the new zip endpoint. **Both must use real navigation
(`window.location`/`<a href download>`), never a JS `fetch()` → blob
pattern** — the latter defeats native browser resumability and is a real
memory problem at GB scale. See File-Storage.md's "Single-file download:
pause/resume" note.

### 6. Deployment

In `CorroboroDeployment/docker-compose.yml` and (mirrored)
`docker-compose.acceptance.yml`: add `vault-db` (Postgres), `vault-clamav`
(prod only — acceptance skips real scanning), `vault-be`, following the
existing `cop-*`/`acc-*` naming/volume/network conventions. **No
gateway-facing port for the vault** — it's reachable only from
`cop-be`/`acc-be` over the internal `cop-private` network, unlike
`cop-fe`/`cop-be`. New secrets (`VAULT_DB_*`, `VAULT_SERVICE_TOKEN` — same
value in both `cop-be`'s and `vault-be`'s env) go in
`.env.example`/`.env.acceptance.example` alongside the existing
`CHANGE_ME_...` placeholders. Acceptance gets its **own full vault
instance** (own DB, own storage volume) — never sharing prod's.

Before finalizing the storage volume's host path: **confirm on the actual
Mac mini (`corroboroserver-mini`) what mechanism, if any, currently copies
`~/BackupSuperMed` off-box** (checked from a dev machine during the vault's
design — found only a local pruned-retention backup script, no
rsync/rclone/SMB step in either repo; it may be an OS-level Time
Machine/Synology client not visible from a code search). Point
`vault.storage.root-path`'s backing volume at whatever that mechanism
actually watches, per File-Storage.md's "Backup / NAS sync" section.

## Verification checklist (from File-Storage.md, once everything above is wired)

1. Create a test appointment; confirm the main backend registers it with
   the vault (a `vault_registration` row + a matching vault-side
   `appointment_registration` row, keyed by the right `(clinicUuid,
   appointmentId)` pair).
2. Upload a real file through the actual patient-facing page (not curl);
   confirm it lands in the vault with `clamScanResult=CLEAN` and shows back
   via the patient's own list.
3. Upload an EICAR test file (with `VAULT_CLAMAV_ENABLED=true` and a real
   `clamd` running); confirm it's rejected with a generic message and never
   written to disk.
4. Reschedule the appointment; confirm the vault's `deleteAfter` moved with
   it. Cancel a different test appointment; confirm further uploads are
   rejected but the doctor can still see/download previously uploaded files.
5. Log into the clinic dashboard, open Documente for that patient, confirm
   vault files appear, a single file downloads (check the network tab for a
   real `206 Partial Content` on a resumed/throttled download), and
   "download all" produces a correct zip.
6. Upload a large (several-hundred-MB) file end-to-end through the real UI;
   confirm it doesn't hit ClamAV's `StreamMaxLength`, either service's
   multipart size limit, or a gateway timeout.

## Conventions to follow (already established, don't reinvent)

- Backend: hexagonal package layout, `*RequestModelV1`/`*ResponseModelV1`
  DTO naming, manual SQL migrations (no Flyway,
  `hibernate.ddl-auto=validate`), `${ENV_VAR:default}` properties.
- Frontend: standalone Angular components, signals, `OnPush` where the
  component is newly written or rewritten, `TranslatePipe` for every
  string (add keys to **all four** locale files —
  `ro/de/en/fr.json` — this repo does not have a `hu.json` despite an
  earlier handoff doc mentioning one).
- Every backend change needs the corresponding entry point checked against
  this backend's existing self-service auth pattern (opaque encrypted
  appointment id, no new patient credential concept) — don't invent a new
  auth scheme for the patient-facing proxy endpoints.
- Match this session's earlier fix: `AppointmentsState`'s
  `findExistingAppointment` cache must stay in sync with reality — if any
  new frontend action changes appointment-adjacent state, make sure a
  stale cache entry doesn't linger (see the recent commit that fixed this
  exact class of bug for update/reschedule).
