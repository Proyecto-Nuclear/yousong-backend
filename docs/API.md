# Yousong HTTP API contract

Contract of the Spring Boot backend for the web admin panel (Next.js) and the Android patron app.

- **Source of truth:** the code at commit `49dde96`. Everything below was read from the controllers, DTOs,
  services, security configuration and `application.properties` of that commit.
- **Examples are real:** every JSON example marked *captured* was produced by the running application (embedded
  PostgreSQL, the real security chain, a local fake YouTube) and copied from the HTTP response. Only ids,
  instants, tokens and video ids were replaced with placeholders such as `<sesionId>`, `<instant>`,
  `pt_<43 chars>` and readable 11-character video ids (`vidCamisa01`). Examples marked *derived* come from the
  DTOs and the code because the case could not be triggered in the capture run.
- **Scope:** only what exists on this commit. Surprising behavior is listed in [Known quirks](#9-known-quirks),
  not fixed.

## Contents

1. [Overview](#1-overview)
2. [Authentication](#2-authentication)
3. [Endpoint reference](#3-endpoint-reference)
4. [Real time (SSE)](#4-real-time-sse)
5. [Queue and playback semantics](#5-queue-and-playback-semantics)
6. [Operational states and flows](#6-operational-states-and-flows)
7. [Client rules and gotchas](#7-client-rules-and-gotchas)
8. [Error catalog](#8-error-catalog)
9. [Known quirks](#9-known-quirks)
10. [Appendix: server settings that change client-visible behavior](#10-appendix-server-settings-that-change-client-visible-behavior)

---

## 1. Overview

### 1.1 Base URL and zones

All paths are absolute from the API origin (dev: `http://localhost:8080`; the Android emulator reaches it as
`http://10.0.2.2:8080`, a physical device through the machine's LAN IP). There is no version prefix.

| Zone | Path prefix | Caller | Credential |
|------|-------------|--------|------------|
| Public | `/api/public/**` | anyone (QR screen, patron app before joining) | none |
| Participant | `/api/participante/**` | anonymous patron who joined a session | `Authorization: Bearer pt_...` (opaque) |
| Owner | `/api/mi-establecimiento/**` | establishment owner (web admin panel / player station) | `Authorization: Bearer <FusionAuth access token>` (JWT) |
| Health | `/actuator/health/**`, `/actuator/info` | anyone | none |

Anything else answers `403` with a valid owner token and `401` without one (deny by default); other
`/actuator/**` endpoints are always `403`.

The two credential types never mix: a `pt_` token on an owner route is rejected by the JWT decoder (`401`,
empty body) and a JWT on a participant route is rejected as `401 INVALID_CREDENTIAL`.

### 1.2 JSON conventions

| Topic | Rule |
|-------|------|
| Media type | Requests with a body: `Content-Type: application/json` (anything else: `415`, Spring default body). Success responses: `application/json`. Errors: `application/problem+json`. |
| Field names | Spanish camelCase (`qrSlug`, `esperaEstimadaSegundos`). Enum values are UPPERCASE Spanish (`ABIERTO`, `SOLICITUD`). |
| Ids | UUID strings (`"123e4567-e89b-12d3-a456-426614174000"`). YouTube video ids: 11 chars `[A-Za-z0-9_-]`. |
| Instants | ISO-8601 UTC with microseconds and `Z`: `"2026-01-31T22:15:00.123456Z"`. Render them in the establishment's time zone (`zonaHoraria`, default `America/Bogota`). |
| Dates | `fechaOperativa` is a plain date `"2026-11-12"` (the operating day in the establishment's zone). |
| Wall-clock times | Schedule windows use `"HH:mm"` in the establishment's zone. |
| Nulls | Fields are present with `null` unless the table says *omitted when null* (`proximaApertura`, `dispositivoToken`, `votado`, `cancion` of a suggestion, `solicitudesBloqueadas`, `reproduciendoActualmente`). |
| Unknown request fields | Ignored (no error). |
| Unknown enum values in requests | `400 INVALID_BODY`. |
| Caching | Every API response carries `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`; the join response carries `Cache-Control: no-store`. |

### 1.3 Error format (RFC 9457)

Every business error is `application/problem+json` with a stable machine-readable `code`. Branch on `code`,
never on `detail` (human text, may change).

| Field | Type | Notes |
|-------|------|-------|
| `type` | string | Only present (`"about:blank"`) on the 401s written by the participant security filter; absent elsewhere. |
| `title` | string | HTTP reason phrase. |
| `status` | int | HTTP status. |
| `detail` | string | Human-readable English text. Never echoes secrets or the search text. |
| `instance` | string | Request path. |
| `code` | string | Stable error code, see [Error catalog](#8-error-catalog). |
| `errors` | array | Only for `VALIDATION_ERROR`: `[{ "field": string, "message": string }]`. |

Captured (`POST /api/mi-establecimiento` with `{"nombre":" ","qrSlug":"AB"}`):

```json
{
  "detail": "The request has invalid fields",
  "instance": "/api/mi-establecimiento",
  "status": 400,
  "title": "Bad Request",
  "code": "VALIDATION_ERROR",
  "errors": [
    { "field": "qrSlug", "message": "must be 3-80 characters: lowercase letters, digits and hyphens" },
    { "field": "nombre", "message": "must not be blank" }
  ]
}
```

Captured (participant credential rejected):

```json
{
  "type": "about:blank",
  "title": "Unauthorized",
  "status": 401,
  "detail": "The session this credential belonged to has ended",
  "instance": "/api/participante/sesion",
  "code": "SESSION_ENDED"
}
```

Responses that are **not** problem details (see [Known quirks](#9-known-quirks)): owner-token 401/403 (empty
body, details in `WWW-Authenticate`), unknown routes 404, 405, 415, a missing required `q` on the participant
search (Spring default JSON `{timestamp,status,error,path}`), and malformed paths (`%00`, `%2F`: Tomcat HTML 400).

Generic request errors, valid on every endpoint:

| Code | Status | When |
|------|--------|------|
| `VALIDATION_ERROR` | 400 | A bean-validation constraint failed; see `errors[]`. |
| `INVALID_BODY` | 400 | Body missing, not JSON, wrong JSON type (object instead of array) or unknown enum value. |
| `INVALID_PARAMETER` | 400 | A path/query parameter has the wrong type (e.g. a non-UUID id). `detail`: `The value of '<name>' is not valid`. |

### 1.4 HTTP status conventions

| Status | Meaning in this API |
|--------|---------------------|
| 200 | Success with body. |
| 201 | Something was created: establishment, default list version, session, participation (join), song request, content block. `Location` is set on establishment, list and session creation. |
| 204 | Leaving a session (`DELETE /api/participante/sesion`). |
| 400 | The request itself is wrong (validation, unknown enum, bad id). Do not retry unchanged. |
| 401 | Missing/invalid credential, or (participants) the session ended. |
| 403 | Route not allowed for any token, or CORS rejection. |
| 404 | The thing does not exist **for this caller** (unknown slug, no establishment yet, no session in operation, id of another session). |
| 409 | Business state forbids it right now (paused, closed, already voted, already playing...). |
| 429 | Rate limit, stream cap or daily search budget. |
| 503 | YouTube catalog unavailable / not configured / quota exhausted, server shutting down, rare join contention. |

### 1.5 CORS

Configured in `security/SecurityConfig` for every path:

| Setting | Value |
|---------|-------|
| Allowed origins | `app.cors.allowed-origins` = env `CORS_ALLOWED_ORIGINS` (comma separated), default `http://localhost:3000`. Exact origins, no wildcard. |
| Allowed methods | `GET, POST, PUT, PATCH, DELETE, OPTIONS` |
| Allowed request headers | `Authorization`, `Content-Type`, `Idempotency-Key` (any other header fails the preflight with `403 Invalid CORS request`) |
| Credentials (cookies) | not allowed; the API uses header tokens only |
| Preflight max age | 3600 s |

Captured preflight from the allowed origin:

```
OPTIONS /api/mi-establecimiento
Origin: http://localhost:3000
Access-Control-Request-Method: PATCH
Access-Control-Request-Headers: authorization,content-type

200
access-control-allow-origin: http://localhost:3000
access-control-allow-methods: GET,POST,PUT,PATCH,DELETE,OPTIONS
access-control-allow-headers: authorization, content-type
access-control-max-age: 3600
```

From `https://evil.example`, or with an extra header such as `x-request-id`: `403` body `Invalid CORS request`.
Android does not send `Origin`, so CORS does not apply to it.

### 1.6 Rate limits and caps

All counters are **in memory, per server instance**, fixed one-minute windows (reset on restart).

| Limit | Default | Scope | Answer |
|-------|---------|-------|--------|
| Joins | 10/min | per client address (`remoteAddr`) | 429 `RATE_LIMITED` "Too many joins from this device..." |
| Joins | 120/min | per establishment | 429 `RATE_LIMITED` "Too many people are joining right now..." |
| Participations | 5000 | per session | 409 `SESSION_FULL` |
| Catalog searches | 6/min | per participation | 429 `RATE_LIMITED` |
| Suggestions | 60/min | per participation | 429 `RATE_LIMITED` |
| Requests + votes together | 10/min | per participation | 429 `RATE_LIMITED` |
| Active requests in the queue | 200 | per session | 409 `QUEUE_FULL` |
| YouTube search budget | 2500 units/day (~24 uncached searches) | per establishment, all its participants together | 429 `SEARCH_BUDGET_EXHAUSTED` |
| Participant share of the YouTube quota | 70 % of 9000 units/day | all establishments | 503 `CATALOG_QUOTA_EXCEEDED` |
| SSE streams | 500 | per establishment | 429 `TOO_MANY_STREAMS` |
| SSE streams | 10 | per client address | 429 `TOO_MANY_STREAMS` |

None of these responses carries `Retry-After`. Back off at least until the next minute for `RATE_LIMITED`;
`SEARCH_BUDGET_EXHAUSTED` lasts until midnight `America/Los_Angeles` (the YouTube quota day) or a server restart.

### 1.7 Idempotency (what is safe to retry)

There is **no idempotency-key support** (the `Idempotency-Key` header is allowed by CORS but ignored). Retry
behavior per operation:

| Operation | Retry after a timeout / network error |
|-----------|---------------------------------------|
| All `GET` | Safe. |
| `PUT /horarios`, `PATCH /api/mi-establecimiento` | Safe (same final state). |
| `PUT /lista-defecto` | **Not idempotent:** every call publishes a new version. Re-read `GET /lista-defecto` first. |
| `POST /api/public/.../participaciones` | Safe **if you send the `dispositivoToken`** you already hold: you get the same participation and a fresh `pt_` token (old ones are revoked). Without it a second participation is created. |
| `POST /api/participante/solicitudes` | A retry of an applied request answers `409 ALREADY_VOTED`: treat as success and refetch the queue. |
| `POST .../votos` | Same as above (`409 ALREADY_VOTED` = already applied). |
| `DELETE /api/participante/sesion` | Safe (a second call needs the same token, which now answers `401 INVALID_CREDENTIAL`). |
| `POST /sesiones` | A retry answers `409 SESSION_ALREADY_OPEN`: read `GET /sesion-actual`. |
| `POST /sesion/cierre` | A retry answers `404 NO_ACTIVE_SESSION` (already closed). |
| Pause/resume/maintenance | A retry answers the matching `409 *_ALREADY_*` / `*_NOT_*` code: treat as applied. |
| `DELETE /bloqueos/{id}` | Idempotent (200 with the current state). |
| `POST /bloqueos` | A retry answers `409 SONG_ALREADY_BLOCKED`. |
| `POST /reproduccion/siguiente` | A retry answers `409 PLAYBACK_IN_PROGRESS`: refetch the queue to see what plays. |
| `POST /reproduccion/fin`, `/saltar` | **Not safe:** a retry may end the *next* song. Refetch `GET .../cola` (`enReproduccion`) before retrying. |
| `DELETE /cola/{solicitudId}` | A retry answers `409 REQUEST_NOT_ACTIVE`. |

---

## 2. Authentication

### 2.1 Owner (FusionAuth)

Owners register and sign in on FusionAuth; this backend is a stateless OAuth2 resource server that only
validates access tokens. Values from `fusionauth/kickstart/kickstart.json` and `application.properties`:

| Fact | Dev value |
|------|-----------|
| Issuer | `http://127.0.0.1:9011` (env `FUSIONAUTH_ISSUER`; the tenant issuer is set to the IdP's own URL so OIDC discovery matches) |
| JWKS | `http://localhost:9011/.well-known/jwks.json` (env `FUSIONAUTH_JWKS_URI`) |
| Client id = application id = expected `aud` | `c2f6a1b4-3d5e-4f70-8a9b-6e1d2c3b4a59` (env `FUSIONAUTH_APPLICATION_ID`) |
| Grants | `authorization_code` (use PKCE), `refresh_token` |
| Client type | confidential (a client secret exists in the dev kickstart; keep it server-side in Next.js) |
| Scopes | `openid offline_access` (`offline_access` is what returns a refresh token) |
| Redirect URIs (dev) | `http://localhost:3000/api/auth/callback/fusionauth`, `http://localhost:3000/` |
| Access token lifetime | 900 s (15 min) |
| Refresh token lifetime | 10080 min (7 days) |
| Signing | RS256, same key for access and ID tokens |
| Self-registration | enabled (email + password); new accounts have **no roles**, and roles are ignored by the API |

**Which token to send:** the **access token** as `Authorization: Bearer <jwt>`. Never the ID token: both are
signed with the same key and audience, so the API checks FusionAuth's reserved claim `tty` and accepts only
`tty = "at"` (ID tokens carry `"idt"` and get `401`).

**Validation rules** (a failure on any of them is `401`):

| Check | Rule |
|-------|------|
| Signature | RS256 against the JWKS |
| `iss` | equals the configured issuer exactly |
| `aud` | contains the application id |
| `exp` | in the future (Spring's default 60 s clock skew applies) |
| `tty` | equals `"at"` |
| `sub` | present, non-blank, at most 64 characters (checked by the API itself: `401 INVALID_TOKEN` problem) |

**401 responses for owners:** the JWT checks run in Spring Security and answer `401` with an **empty body**; the
reason is only in `WWW-Authenticate` (captured):

```
401   (no token)
www-authenticate: Bearer resource_metadata="http://<api-host>/.well-known/oauth-protected-resource"

401   (ID token sent)
www-authenticate: Bearer error="invalid_token", error_description="An error occurred while attempting to decode the Jwt: The tty claim is not valid", error_uri="https://tools.ietf.org/html/rfc6750#section-3.1", resource_metadata="..."

401   (wrong audience)
www-authenticate: Bearer error="invalid_token", error_description="An error occurred while attempting to decode the Jwt: The aud claim is not valid", ...

401   (garbage or a pt_ token)
www-authenticate: Bearer error="invalid_token", error_description="An error occurred while attempting to decode the Jwt: Malformed token", ...
```

The only owner 401 with a problem body is `INVALID_TOKEN` (valid token, unusable `sub`), captured:

```json
{"detail":"The access token does not identify an account","instance":"/api/mi-establecimiento","status":401,"title":"Unauthorized","code":"INVALID_TOKEN"}
```

Client handling: on any owner `401`, refresh the access token once and retry; if it still fails, send the
user back to login. Refresh proactively before the 15-minute expiry.

**Ownership is the only authorization.** The establishment an owner acts on is the one whose
`propietario_sub` equals the token's `sub`; no owner route accepts an establishment or session id. One account
owns at most one establishment. Until the account creates it, every owner route except `POST
/api/mi-establecimiento` answers `404 NO_ESTABLISHMENT` (captured):

```json
{"detail":"This account has no establishment yet: create it with POST /api/mi-establecimiento","instance":"/api/mi-establecimiento","status":404,"title":"Not Found","code":"NO_ESTABLISHMENT"}
```

### 2.2 Patron (participant credential)

Patrons never register. The flow behind the QR:

```
scan QR -> {PUBLIC_BASE_URL}/s/{qrSlug}
  1. GET  /api/public/establecimientos/{qrSlug}          -> servicio, puedeParticipar
  2. GET  /api/public/politica                            -> version + purposes F1/F2 (show them)
  3. POST /api/public/establecimientos/{qrSlug}/participaciones
         {canal, politicaVersion, autorizaF1:true, autorizaF2?, dispositivoToken?}
       <- 201 {token: "pt_...", expiraAt, seudonimo, servicio, establecimiento, dispositivoToken?}
  4. use Authorization: Bearer pt_... on /api/participante/**
```

**Participant token (`pt_`)**

| Property | Value |
|----------|-------|
| Format | `pt_` + 43 base64url chars (256 random bits), opaque |
| Shown | once, in the join response (`Cache-Control: no-store`); only its SHA-256 is stored |
| Sent as | `Authorization: Bearer pt_...` (scheme case-insensitive) |
| Lifetime | `expiraAt` = join time + 24 h (`app.participante.credential-ttl-hours`), **and** it dies the moment its session closes |
| Replaced by | a later join of the same device (the previous token becomes `INVALID_CREDENTIAL`) |
| Revoked by | `DELETE /api/participante/sesion` (leave) |

**Rejections** (problem with `WWW-Authenticate: Bearer`, `Cache-Control: no-store`):

| Code | Meaning | Client action |
|------|---------|---------------|
| `401 SESSION_ENDED` | The session this token belonged to is closed. Checked first, so it wins over every other reason. | **Terminal:** delete the `pt_` token and session UI state, show "the session ended", go back to the scan screen. Keep the `dv_` token. |
| `401 INVALID_CREDENTIAL` | Missing header, malformed, unknown, expired, revoked by leaving, replaced by a rejoin, or its device expired. | Delete the `pt_` token and rejoin (`POST .../participaciones` with the stored `dv_` token) if the user is still at the venue; otherwise go to the scan screen. |

**Device token (`dv_`)**

| Property | Value |
|----------|-------|
| Format | `dv_` + 43 base64url chars, opaque; derived from nothing about the phone |
| Returned | only when a join **mints a new device** (first join, or the sent token was missing/unknown/expired/revoked/malformed). A recognized device never gets it again (field omitted). |
| Storage | store it securely and permanently (Android: Keystore-backed `EncryptedSharedPreferences`/DataStore; web: the best you have, e.g. `localStorage`) and send it as `dispositivoToken` on **every** later join, at any establishment |
| Effect | the same phone gets back its participation in the same session (same `seudonimo`, same votes) instead of a second one, also after leaving |
| Lifetime | 30 days sliding: every successful identification renews it |
| Losing it | the next join creates a new device and a new participation (new pseudonym, the old votes stay with the old one) |
| Invalid value | silently treated as "no device" (no error, a new `dv_` is returned): always overwrite the stored value when the response contains one |

**Consent fields**

| Field | Rule | Error |
|-------|------|-------|
| `autorizaF1` | must be `true` (purpose F1 is required to participate) | `400 CONSENT_REQUIRED` |
| `autorizaF2` | optional; recorded only when `true` | - |
| `politicaVersion` | must equal the current `version` of `GET /api/public/politica` (dev `1.0`, `app.privacy.policy-version`) | `409 POLICY_VERSION_MISMATCH`: refetch the policy, show it again, ask for consent again |

**Join errors**, in the order the server checks them (the first failing rule wins):

| # | Code | Status | Condition |
|---|------|--------|-----------|
| 1 | `VALIDATION_ERROR` / `INVALID_BODY` | 400 | missing `canal`/`politicaVersion`, `politicaVersion` > 20 chars, unknown `canal` |
| 2 | `CONSENT_REQUIRED` | 400 | `autorizaF1` not `true` |
| 3 | `POLICY_VERSION_MISMATCH` | 409 | old policy version |
| 4 | `ESTABLISHMENT_NOT_FOUND` | 404 | unknown slug |
| 5 | `RATE_LIMITED` | 429 | join limits (per address, then per establishment) |
| 6 | `ESTABLISHMENT_INACTIVE` | 409 | owner deactivated the establishment |
| 7 | `ESTABLISHMENT_IN_MAINTENANCE` | 409 | maintenance |
| 8 | `ESTABLISHMENT_PAUSED` | 409 | system pause |
| 9 | `OUTSIDE_SCHEDULE` | 409 | windows exist and now is outside all of them (even if a session is open) |
| 10 | `NO_ACTIVE_SESSION` | 409 | no session in operation (note: 409 here, 404 on owner routes) |
| 11 | `SESSION_PAUSED` | 409 | the session is paused |
| 12 | `SESSION_FULL` | 409 | the session reached its participation cap (only for a new participation) |
| 13 | `JOIN_UNAVAILABLE` | 503 | could not allocate a pseudonym after 10 tries; retry shortly |

---

## 3. Endpoint reference

Notation: **Who** = `public`, `participant` (`pt_` token), `owner` (access token). "Events" lists the
integration/outbox events written and whether SSE subscribers get a new snapshot ("SSE push").

### 3.1 Shared shapes

**`CancionCola`** (a song as the queue shows it)

| Field | Type | Notes |
|-------|------|-------|
| `videoId` | string | YouTube id |
| `titulo` | string \| null | null until the catalog has metadata |
| `artista` | string \| null | display label: verified artist, else the channel without ` - Topic`/`VEVO` |
| `artistaVerificado` | boolean | true only when the song has a verified artist |
| `duracionSegundos` | int \| null | provider duration |
| `miniaturaUrl` | string \| null | thumbnail URL |

**`CancionBusqueda`** (search result, suggestion song, list item use the same fields): `CancionCola` plus
`canal` (string \| null, raw channel title).

**`Establecimiento`** (`EstablecimientoResponse`)

| Field | Type | Notes |
|-------|------|-------|
| `id` | uuid | |
| `nombre` | string | 1-120 chars |
| `qrSlug` | string | `[a-z0-9-]{3,80}`, immutable |
| `zonaHoraria` | string | IANA zone |
| `activo` | boolean | false = closed to the public |
| `pausado` | boolean | system pause |
| `enMantenimiento` | boolean | maintenance |
| `creadoAt` | instant | |

**`Sesion`** (`SesionResponse`)

| Field | Type | Notes |
|-------|------|-------|
| `id` | uuid | |
| `establecimientoId` | uuid | |
| `listaId` | uuid | default-list version pinned when the session opened |
| `fechaOperativa` | date | operating day in the establishment's zone |
| `estado` | enum | `ABIERTA`, `PAUSADA`, `CERRANDO`, `CERRADA` |
| `abiertaAt` | instant | |
| `cerradaAt` | instant \| null | |

### 3.2 Public (`/api/public/**`, no credential)

#### GET `/api/public/establecimientos/{qrSlug}` - establishment status behind the QR

Who: public. Response `200`:

| Field | Type | Notes |
|-------|------|-------|
| `nombre` | string | |
| `qrSlug` | string | |
| `servicio` | enum | `ABIERTO` \| `PAUSADO` \| `CERRADO` \| `MANTENIMIENTO`. Treat any other value as "cannot participate". |
| `puedeParticipar` | boolean | `servicio == ABIERTO` |
| `proximaApertura` | instant | *omitted when null*. Only when `CERRADO` **because of the weekly schedule**: start of the next window (searched up to 7 days ahead). |

`servicio` precedence: inactive -> `CERRADO`; maintenance -> `MANTENIMIENTO`; system pause -> `PAUSADO`; outside
every weekly window -> `CERRADO` + `proximaApertura`; otherwise the session in operation (`ABIERTA` ->
`ABIERTO`, `PAUSADA` -> `PAUSADO`, `CERRANDO`/none -> `CERRADO`).

Captured:

```json
{"nombre":"Bar La Esquina","qrSlug":"bar-la-esquina-d40cc0","servicio":"ABIERTO","puedeParticipar":true}
```

```json
{"nombre":"Bar La Esquina","qrSlug":"bar-la-esquina-d40cc0","servicio":"CERRADO","puedeParticipar":false,"proximaApertura":"<instant>"}
```

Errors: `404 ESTABLISHMENT_NOT_FOUND`.

#### GET `/api/public/politica` - current privacy policy

Who: public. Response `200`: `version` (string), `finalidades[]` with `codigo` (`F1` \| `F2`) and `descripcion`
(English text). Captured:

```json
{
  "version": "1.0",
  "finalidades": [
    {"codigo": "F1", "descripcion": "Participation in the music session: request and vote for songs, control of the supports you use, operation of the service, handling of incidents and session statistics. Required to participate."},
    {"codigo": "F2", "descripcion": "Voluntary feedback about the usability of the application. Optional and separate: you can participate without it."}
  ]
}
```

#### POST `/api/public/establecimientos/{qrSlug}/participaciones` - join the session

Who: public. Request:

| Field | Type | Required | Constraints | Notes |
|-------|------|----------|-------------|-------|
| `canal` | enum | yes | `WEB` \| `ANDROID` | |
| `politicaVersion` | string | yes | not blank, max 20 | must equal the current policy version |
| `autorizaF1` | boolean | yes (must be `true`) | | |
| `autorizaF2` | boolean | no | | recorded only when `true` |
| `dispositivoToken` | string | no | | the stored `dv_` token |

Response `201` (`Cache-Control: no-store`):

| Field | Type | Notes |
|-------|------|-------|
| `token` | string | `pt_...`, shown only here |
| `expiraAt` | instant | hard expiry of the token (the session close ends it earlier) |
| `seudonimo` | string | random pseudonym, stable per device and session |
| `servicio` | enum | always `ABIERTO` on success |
| `establecimiento.nombre` | string | |
| `dispositivoToken` | string | *omitted when null*; present only when a new device was minted |

Captured, first join:

```json
{"token":"pt_<43 chars>","expiraAt":"<instant>","seudonimo":"p-b8eca1","servicio":"ABIERTO","establecimiento":{"nombre":"Bar La Esquina"},"dispositivoToken":"dv_<43 chars>"}
```

Captured, rejoin sending that `dispositivoToken` (same pseudonym, no new `dispositivoToken`):

```json
{"token":"pt_<43 chars, new>","expiraAt":"<instant>","seudonimo":"p-b8eca1","servicio":"ABIERTO","establecimiento":{"nombre":"Bar La Esquina"}}
```

Errors: see [join errors](#22-patron-participant-credential). Captured examples:

```json
{"detail":"Purpose F1 of the privacy policy must be authorized to participate","instance":"/api/public/establecimientos/bar-la-esquina-d40cc0/participaciones","status":400,"title":"Bad Request","code":"CONSENT_REQUIRED"}
{"detail":"The privacy policy changed; the current version is 1.0","instance":"...","status":409,"title":"Conflict","code":"POLICY_VERSION_MISMATCH"}
{"detail":"The establishment is outside its opening hours","instance":"...","status":409,"title":"Conflict","code":"OUTSIDE_SCHEDULE"}
{"detail":"The establishment has no session open to participants","instance":"...","status":409,"title":"Conflict","code":"NO_ACTIVE_SESSION"}
{"detail":"The session is paused; try again when it resumes","instance":"...","status":409,"title":"Conflict","code":"SESSION_PAUSED"}
{"detail":"The establishment is paused; try again when it resumes","instance":"...","status":409,"title":"Conflict","code":"ESTABLISHMENT_PAUSED"}
{"detail":"The establishment is in maintenance","instance":"...","status":409,"title":"Conflict","code":"ESTABLISHMENT_IN_MAINTENANCE"}
{"detail":"The establishment is not active","instance":"...","status":409,"title":"Conflict","code":"ESTABLISHMENT_INACTIVE"}
```

Side effects: creates or reuses device + participation, records consents (F1 always under the current version,
F2 when authorized), revokes the device's previous credentials in this session, issues a credential. No SSE push.

```bash
curl -s -X POST http://localhost:8080/api/public/establecimientos/bar-la-esquina-d40cc0/participaciones \
  -H 'Content-Type: application/json' \
  -d '{"canal":"ANDROID","politicaVersion":"1.0","autorizaF1":true,"autorizaF2":false,"dispositivoToken":"'"$DV"'"}'
```

#### GET `/api/public/establecimientos/{qrSlug}/cola` - public queue snapshot

Who: public. Response `200` `ColaResponse`:

| Field | Type | Notes |
|-------|------|-------|
| `colaVersion` | long | queue version of the session in operation; `0` when there is none |
| `estado` | enum | same value as `servicio` of the status endpoint |
| `enReproduccion` | object \| null | what plays now, see below |
| `cola` | array | waiting items in play order (first plays next) |
| `proximasDefecto` | array | the next default-list songs, after every request of `cola`; see below |
| `rotacionDefecto` | enum \| null | `SECUENCIAL` \| `ALEATORIA` (policy of the list the session pinned); `null` without a session in operation or when nothing of the list is playable |

`enReproduccion`: `solicitudId` (uuid \| null, null for a default-list song), `origen` (`SOLICITUD` \|
`DEFECTO`), `cancion` (`CancionCola`), `iniciadaAt` (instant), `restanteSegundos` (long, estimate),
`votado` (participant view only).

`cola[]` item: `solicitudId` (uuid), `posicion` (int, 1-based), `votos` (long), `cancion` (`CancionCola`),
`esperaEstimadaSegundos` (long), `votado` (participant view only).

`proximasDefecto[]` item: `posicion` (int), `cancion` (`CancionCola`), `esperaEstimadaSegundos` (long). No
`solicitudId`, `votos` or `votado` (a default song is not a request and cannot be supported), in the participant
view too. At most 5 items (`app.cola.default-preview-size`), in the order the rotation will pick them (wrapping
around the list), skipping blocked and unavailable songs. `posicion` continues after the requests (2 waiting
requests: the first default song is position 3), and its wait counts what is left of the song playing, every
request and the default songs ahead of it. `ALEATORIA` cannot be predicted, so its preview is always `[]`.
Without a session in operation, or when nothing of the list is playable, it is `[]` too. A session keeps the
default-list version it opened with: publishing a new version changes the preview from the next session only.

No identity (participation, device, pseudonym) appears anywhere: safe for a public screen.

Captured (a song playing, two waiting; captured before the preview fields existed, current responses also end
with `proximasDefecto` and `rotacionDefecto` as in the next capture):

```json
{
  "colaVersion": 6,
  "estado": "ABIERTO",
  "enReproduccion": {
    "solicitudId": "<solicitudId-A>",
    "origen": "SOLICITUD",
    "cancion": {"videoId":"vidSongA001","titulo":"Titulo vidSongA001","artista":"Canal","artistaVerificado":false,"duracionSegundos":200,"miniaturaUrl":"https://img.test/vidSongA001/mqdefault.jpg"},
    "iniciadaAt": "<instant>",
    "restanteSegundos": 200
  },
  "cola": [
    {"solicitudId":"<solicitudId-B>","posicion":1,"votos":1,"cancion":{"videoId":"vidSongB001","titulo":"Titulo vidSongB001","artista":"Canal","artistaVerificado":false,"duracionSegundos":200,"miniaturaUrl":"https://img.test/vidSongB001/mqdefault.jpg"},"esperaEstimadaSegundos":200},
    {"solicitudId":"<solicitudId-C>","posicion":2,"votos":1,"cancion":{"videoId":"vidSongC001","titulo":"Titulo vidSongC001","artista":"Canal","artistaVerificado":false,"duracionSegundos":200,"miniaturaUrl":"https://img.test/vidSongC001/mqdefault.jpg"},"esperaEstimadaSegundos":400}
  ]
}
```

Captured (one request waiting, nothing playing, a `SECUENCIAL` list of three songs: 227, 250 and 252 s):

```json
{
  "colaVersion": 1,
  "estado": "ABIERTO",
  "enReproduccion": null,
  "cola": [
    {"solicitudId":"<solicitudId-A>","posicion":1,"votos":1,"cancion":{"videoId":"vidSongA001","titulo":"Titulo vidSongA001","artista":"Canal","artistaVerificado":false,"duracionSegundos":200,"miniaturaUrl":"https://img.test/vidSongA001/mqdefault.jpg"},"esperaEstimadaSegundos":0}
  ],
  "proximasDefecto": [
    {"posicion":2,"cancion":{"videoId":"vidDefect01","titulo":"Titulo vidDefect01","artista":"Canal","artistaVerificado":false,"duracionSegundos":227,"miniaturaUrl":"https://img.test/vidDefect01/mqdefault.jpg"},"esperaEstimadaSegundos":200},
    {"posicion":3,"cancion":{"videoId":"vidDefect02","titulo":"Titulo vidDefect02","artista":"Canal","artistaVerificado":false,"duracionSegundos":250,"miniaturaUrl":"https://img.test/vidDefect02/mqdefault.jpg"},"esperaEstimadaSegundos":427},
    {"posicion":4,"cancion":{"videoId":"vidDefect03","titulo":"Titulo vidDefect03","artista":"Canal","artistaVerificado":false,"duracionSegundos":252,"miniaturaUrl":"https://img.test/vidDefect03/mqdefault.jpg"},"esperaEstimadaSegundos":677}
  ],
  "rotacionDefecto": "SECUENCIAL"
}
```

Captured (`ALEATORIA` list, empty queue):
`{"colaVersion":0,"estado":"ABIERTO","enReproduccion":null,"cola":[],"proximasDefecto":[],"rotacionDefecto":"ALEATORIA"}`

Captured (no session in operation):
`{"colaVersion":0,"estado":"CERRADO","enReproduccion":null,"cola":[],"proximasDefecto":[],"rotacionDefecto":null}`

Errors: `404 ESTABLISHMENT_NOT_FOUND`.

#### GET `/api/public/establecimientos/{qrSlug}/cola/eventos` - live queue (SSE)

Who: public. `text/event-stream`. See [Real time (SSE)](#4-real-time-sse).
Errors before the stream starts: `404 ESTABLISHMENT_NOT_FOUND`, `429 TOO_MANY_STREAMS`, `503 SHUTTING_DOWN`.

### 3.3 Participant (`/api/participante/**`, `Authorization: Bearer pt_...`)

Every route answers `401 SESSION_ENDED` once the session closes and `401 INVALID_CREDENTIAL` for any other
credential problem (including unknown routes under this prefix without a valid credential).

#### GET `/api/participante/sesion` - my session

Response `200`: `establecimiento.nombre` (string), `servicio` (enum, the same value as `servicio` of the public
status endpoint: inactive, maintenance, system pause and the weekly schedule apply first, then the session
state), `seudonimo` (string). Poll it (or use the SSE stream + this call) to notice the end of the session. Captured:

```json
{"establecimiento":{"nombre":"Bar La Esquina"},"servicio":"ABIERTO","seudonimo":"p-b8eca1"}
```

During a session pause: `"servicio":"PAUSADO"` (captured). Also `PAUSADO` during a system pause, and `CERRADO`
outside every weekly window while the session is still open.

#### DELETE `/api/participante/sesion` - leave

Response `204`, empty. The credential stops working (`401 INVALID_CREDENTIAL` afterwards); the participation,
its votes and the device stay, so rejoining with the `dv_` token returns the same participation.

#### GET `/api/participante/catalogo/busqueda?q=&pagina=` - YouTube search

| Param | Required | Constraints | Notes |
|-------|----------|-------------|-------|
| `q` | yes | 2-100 chars after trimming and collapsing spaces | missing: Spring default 400 (not a problem, see quirks) |
| `pagina` | no | `[A-Za-z0-9_=-]{1,100}` | the previous response's `siguientePagina` |

Response `200`: `resultados[]` (`CancionBusqueda`, only playable videos), `siguientePagina` (string \| null).
Captured:

```json
{"resultados":[{"videoId":"vidBohemi01","titulo":"Bohemian Rhapsody","artista":"Queen","artistaVerificado":false,"canal":"Queen - Topic","duracionSegundos":355,"miniaturaUrl":"https://img.test/vidBohemi01/mqdefault.jpg"},{"videoId":"vidBohemi02","titulo":"Bohemian Rhapsody (Live Aid)","artista":"Queen Official","artistaVerificado":false,"canal":"Queen Official","duracionSegundos":362,"miniaturaUrl":"https://img.test/vidBohemi02/mqdefault.jpg"}],"siguientePagina":"CAMQAA"}
```

Errors: `400 INVALID_QUERY`, `400 INVALID_PAGE`, `429 RATE_LIMITED` (6/min, checked before anything else, costs
no quota), `429 SEARCH_BUDGET_EXHAUSTED`, `503 CATALOG_QUOTA_EXCEEDED`, `503 CATALOG_UNAVAILABLE`, `503
CATALOG_NOT_CONFIGURED`. Captured:

```json
{"detail":"The query must have between 2 and 100 characters","instance":"/api/participante/catalogo/busqueda","status":400,"title":"Bad Request","code":"INVALID_QUERY"}
{"detail":"Too many searches: at most 6 per minute. Try again in a moment","instance":"/api/participante/catalogo/busqueda","status":429,"title":"Too Many Requests","code":"RATE_LIMITED"}
{"detail":"This place has used its catalog searches for today; ask the staff for your song","instance":"/api/participante/catalogo/busqueda","status":429,"title":"Too Many Requests","code":"SEARCH_BUDGET_EXHAUSTED"}
```

Cost: an identical query (case-insensitive, same page) within 60 minutes is answered from a server-wide cache
and costs nothing; a miss costs 100 + 1 YouTube units, charged to the establishment's daily budget.

#### GET `/api/participante/catalogo/sugerencias?q=` - search-as-you-type

| Param | Required | Constraints |
|-------|----------|-------------|
| `q` | yes | 2-100 chars (missing or short: `400 INVALID_QUERY`) |

Response `200`: `sugerencias[]`, at most 8: first up to 5 known songs (`tipo: "CANCION"`, `texto` = title,
`cancion` = `CancionBusqueda`), then up to 5 search strings from YouTube's unofficial suggest source
(`tipo: "BUSQUEDA"`, `texto`, no `cancion`). Free (no YouTube Data API quota). A `CANCION` suggestion can be
requested directly with its `videoId`; a `BUSQUEDA` one should fill the search box. Captured:

```json
{"sugerencias":[{"tipo":"CANCION","texto":"La camisa negra","cancion":{"videoId":"vidCamisa01","titulo":"La camisa negra","artista":"Juanes","artistaVerificado":false,"canal":"Juanes - Topic","duracionSegundos":227,"miniaturaUrl":"https://img.test/vidCamisa01/mqdefault.jpg"}},{"tipo":"BUSQUEDA","texto":"bohemian rhapsody"},{"tipo":"BUSQUEDA","texto":"bohemian rhapsody queen"},{"tipo":"BUSQUEDA","texto":"bohemian rhapsody lyrics"}]}
```

Errors: `400 INVALID_QUERY`, `429 RATE_LIMITED` (60/min). The remote part silently disappears when the suggest
source fails (never an error).

#### GET `/api/participante/cola` - the queue, with my votes

Same `ColaResponse` as the public queue, plus `votado` (boolean) on `enReproduccion` and on every `cola[]` item:
true when this participation already supported **that song** in this session. `estado` is the same value the
public queue and the public status report (system pause, maintenance and the weekly schedule included).
`proximasDefecto` and `rotacionDefecto` are the same as in the public queue (no `votado` on preview items).
Captured:

```json
{"colaVersion":4,"estado":"ABIERTO","enReproduccion":null,"cola":[{"solicitudId":"<solicitudId-A>","posicion":1,"votos":3,"cancion":{"videoId":"vidSongA001","titulo":"Titulo vidSongA001","artista":"Canal","artistaVerificado":false,"duracionSegundos":200,"miniaturaUrl":"https://img.test/vidSongA001/mqdefault.jpg"},"esperaEstimadaSegundos":0,"votado":true},{"solicitudId":"<solicitudId-B>","posicion":2,"votos":1,"cancion":{"videoId":"vidSongB001","titulo":"Titulo vidSongB001","artista":"Canal","artistaVerificado":false,"duracionSegundos":200,"miniaturaUrl":"https://img.test/vidSongB001/mqdefault.jpg"},"esperaEstimadaSegundos":200,"votado":false}]}
```

That capture predates the preview fields. Captured with a default-list preview (the request was made by this
participant; the preview items carry no `votado`):

```json
{"colaVersion":1,"estado":"ABIERTO","enReproduccion":null,"cola":[{"solicitudId":"<solicitudId-A>","posicion":1,"votos":1,"cancion":{"videoId":"vidSongA001","titulo":"Titulo vidSongA001","artista":"Canal","artistaVerificado":false,"duracionSegundos":200,"miniaturaUrl":"https://img.test/vidSongA001/mqdefault.jpg"},"esperaEstimadaSegundos":0,"votado":true}],"proximasDefecto":[{"posicion":2,"cancion":{"videoId":"vidDefect01","titulo":"Titulo vidDefect01","artista":"Canal","artistaVerificado":false,"duracionSegundos":227,"miniaturaUrl":"https://img.test/vidDefect01/mqdefault.jpg"},"esperaEstimadaSegundos":200},{"posicion":3,"cancion":{"videoId":"vidDefect02","titulo":"Titulo vidDefect02","artista":"Canal","artistaVerificado":false,"duracionSegundos":250,"miniaturaUrl":"https://img.test/vidDefect02/mqdefault.jpg"},"esperaEstimadaSegundos":427},{"posicion":4,"cancion":{"videoId":"vidDefect03","titulo":"Titulo vidDefect03","artista":"Canal","artistaVerificado":false,"duracionSegundos":252,"miniaturaUrl":"https://img.test/vidDefect03/mqdefault.jpg"},"esperaEstimadaSegundos":677}],"rotacionDefecto":"SECUENCIAL"}
```

#### POST `/api/participante/solicitudes` - request a song (or vote for it)

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `videoId` | string | yes | 11 chars `[A-Za-z0-9_-]` (a search result or suggestion `videoId`) |

Response `ApoyoResponse`, **`201`** when this call created the request (with the caller's first vote), **`200`**
when the song already had an active request and this call voted for it:

| Field | Type | Notes |
|-------|------|-------|
| `solicitudId` | uuid | the request that now holds the vote |
| `votos` | long | votes after this call |
| `votado` | boolean | always `true` on success |
| `posicion` | int \| null | 1-based place in the queue; null while that request is playing |
| `creada` | boolean | true = created (201), false = vote (200) |

Captured:

```json
{"solicitudId":"<solicitudId-A>","votos":1,"votado":true,"posicion":1,"creada":true}
{"solicitudId":"<solicitudId-A>","votos":2,"votado":true,"posicion":1,"creada":false}
```

A song the catalog has never stored is first looked up on YouTube (1 unit, charged to the establishment's
budget): unknown on YouTube -> `404 SONG_NOT_FOUND`, not playable -> `409 SONG_UNAVAILABLE`.

Errors (captured unless marked): `400 INVALID_VIDEO_ID`, `400 INVALID_BODY` (no body), `404 SONG_NOT_FOUND`,
`409 ALREADY_VOTED`, `409 SONG_RECENTLY_PLAYED`, `409 SONG_BLOCKED`, `409 SONG_UNAVAILABLE` (derived), `409
QUEUE_FULL` (derived), `409 SESSION_PAUSED`, `409 SESSION_NOT_ACCEPTING` (derived), `409 ESTABLISHMENT_PAUSED`,
`409 ESTABLISHMENT_IN_MAINTENANCE` (derived), `409 REQUEST_CONFLICT` (derived, backstop), `429 RATE_LIMITED`
(derived message: "Too many requests and votes: at most 10 per minute. Try again in a moment"), `429
SEARCH_BUDGET_EXHAUSTED` and `503 CATALOG_*` (only for an unknown song lookup).

```json
{"detail":"'abc' is not a YouTube video id (11 characters: letters, digits, '-' or '_')","instance":"/api/participante/solicitudes","status":400,"title":"Bad Request","code":"INVALID_VIDEO_ID"}
{"detail":"You already supported this song in this session","instance":"/api/participante/solicitudes","status":409,"title":"Conflict","code":"ALREADY_VOTED"}
{"detail":"This song played less than 30 minutes ago; it can be requested again later","instance":"/api/participante/solicitudes","status":409,"title":"Conflict","code":"SONG_RECENTLY_PLAYED"}
{"detail":"This song is blocked in this establishment","instance":"/api/participante/solicitudes","status":409,"title":"Conflict","code":"SONG_BLOCKED"}
{"detail":"The video '<videoId>' does not exist on YouTube","instance":"/api/participante/solicitudes","status":404,"title":"Not Found","code":"SONG_NOT_FOUND"}
```

Events: `request.created` (new), `vote.accepted`, `queue.changed`; SSE push.

```bash
curl -s -X POST http://localhost:8080/api/participante/solicitudes \
  -H "Authorization: Bearer $PT" -H 'Content-Type: application/json' -d '{"videoId":"vidCamisa01"}'
```

#### POST `/api/participante/solicitudes/{solicitudId}/votos` - vote for an active request

No body. Response `200` `ApoyoResponse` (`creada: false`). Captured:

```json
{"solicitudId":"<solicitudId-A>","votos":3,"votado":true,"posicion":1,"creada":false}
```

Errors: `400 INVALID_PARAMETER` (non-UUID), `404 REQUEST_NOT_FOUND` (unknown or of another session), `409
ALREADY_VOTED`, `409 SONG_BLOCKED`, `409 SONG_UNAVAILABLE`, `409 REQUEST_NOT_ACTIVE` (already played, skipped,
removed), the session/establishment `409`s of the previous endpoint, `429 RATE_LIMITED`. A vote can also land
on the request that is playing (it counts but does not change anything visible except `votos`).
Events: `vote.accepted`, `queue.changed`; SSE push.

### 3.4 Owner: establishment (`/api/mi-establecimiento`)

#### POST `/api/mi-establecimiento` - create my establishment

| Field | Type | Required | Constraints | Notes |
|-------|------|----------|-------------|-------|
| `nombre` | string | yes | not blank, max 120 | stored trimmed |
| `qrSlug` | string | no | `[a-z0-9-]{3,80}` | default: name slugified (accents removed) + `-` + 6 random chars; **cannot be changed later** |
| `zonaHoraria` | string | no | IANA zone, max 64 | default `America/Bogota` |

Response `201`, `Location: /api/mi-establecimiento`, body `Establecimiento`. Captured:

```json
{"id":"<establecimientoId>","nombre":"Bar La Esquina","qrSlug":"bar-la-esquina-d40cc0","zonaHoraria":"America/Bogota","activo":true,"pausado":false,"enMantenimiento":false,"creadoAt":"<instant>"}
```

Errors: `400 VALIDATION_ERROR`, `400 INVALID_TIME_ZONE`, `409 ESTABLISHMENT_ALREADY_EXISTS`, `409 SLUG_TAKEN`
(derived). Events: `configuration.changed`.

```bash
curl -s -X POST http://localhost:8080/api/mi-establecimiento \
  -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' -d '{"nombre":"Bar La Esquina"}'
```

#### GET `/api/mi-establecimiento`

Response `200` `Establecimiento`. Errors: `404 NO_ESTABLISHMENT`.

#### PATCH `/api/mi-establecimiento` - update

Only the fields sent are changed; at least one is required. `qrSlug` is not editable (ignored like any unknown
field).

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| `nombre` | string | max 120, not only spaces | |
| `zonaHoraria` | string | IANA, max 64 | |
| `activo` | boolean | | `false` is refused while a session is in operation |

Response `200` `Establecimiento`. Errors: `400 EMPTY_UPDATE`, `400 VALIDATION_ERROR`, `400 INVALID_TIME_ZONE`,
`409 SESSION_IN_PROGRESS`. Captured:

```json
{"detail":"Send at least one of: nombre, zonaHoraria, activo","instance":"/api/mi-establecimiento","status":400,"title":"Bad Request","code":"EMPTY_UPDATE"}
{"detail":"'Mars/Base' is not a known IANA time zone","instance":"/api/mi-establecimiento","status":400,"title":"Bad Request","code":"INVALID_TIME_ZONE"}
{"detail":"Close the session in operation before deactivating the establishment","instance":"/api/mi-establecimiento","status":409,"title":"Conflict","code":"SESSION_IN_PROGRESS"}
```

Events: `configuration.changed` (only when something changed). No SSE push.

#### GET `/api/mi-establecimiento/qr` - printable QR

Response `200`: `url` (string, `{PUBLIC_BASE_URL}/s/{qrSlug}`), `svg` (string, a self-contained SVG, ~4 KB).
Works with or without a session. Captured (SVG shortened):

```json
{"url":"https://yousong.test/s/bar-la-esquina-d40cc0","svg":"<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 41 41\" shape-rendering=\"crispEdges\" role=\"img\" aria-label=\"Código QR de acceso a la sesión\"><rect .../><path .../></svg>"}
```

Render the SVG as an image (e.g. `data:image/svg+xml` or `dangerouslySetInnerHTML` of trusted server output),
not as text.

### 3.5 Owner: weekly schedule

#### GET `/api/mi-establecimiento/horarios`

Response `200`: array of windows, sorted by day then start. Captured: `[]` or

```json
[{"diaSemana":6,"inicio":"20:00","fin":"02:00","cruzaMedianoche":true},{"diaSemana":7,"inicio":"18:00","fin":"23:30","cruzaMedianoche":false}]
```

#### PUT `/api/mi-establecimiento/horarios` - replace the whole weekly set

Body: JSON **array** (max 21; `[]` removes every window = no restriction):

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `diaSemana` | int | yes | 1 = Monday ... 7 = Sunday |
| `inicio` | string | yes | `HH:mm` (two-digit hour) in the establishment's zone |
| `fin` | string | yes | `HH:mm`; `fin <= inicio` means it ends the next day; `fin == inicio` is invalid |

Response `200`: the stored windows (`cruzaMedianoche` derived). The set is validated as a whole; a rejected
request changes nothing.

Errors (captured): `400 INVALID_SCHEDULE` (detail names the problem: `diaSemana must be 1 (Monday) to 7
(Sunday)`, `inicio must be a time formatted as HH:mm`, `A window cannot start and end at the same time`), `400
SCHEDULE_OVERLAP` (`Window 1 overlaps window 2`, also across midnight and Sunday->Monday; touching ends are
fine), `400 TOO_MANY_WINDOWS` (derived), `400 INVALID_BODY` (not an array).
Events: `configuration.changed`. No SSE push (see quirks).

Effects: with at least one window, patrons can only join inside a window (`OUTSIDE_SCHEDULE` otherwise) and a
session opened inside a window is closed automatically when the establishment leaves every window (see 6.1).

### 3.6 Owner: default list

#### PUT `/api/mi-establecimiento/lista-defecto` - publish a new version

| Field | Type | Required | Constraints | Notes |
|-------|------|----------|-------------|-------|
| `nombre` | string | no | max 100 | default `"General"` (blank -> default) |
| `politicaRotacion` | enum | no | `SECUENCIAL` \| `ALEATORIA` | default `SECUENCIAL` |
| `canciones` | string[] | yes | 1-200 video ids, no duplicates | play order (position 1 first) |

Every call creates a **new version** (`version` = previous max for that name + 1); lists are never edited.
Metadata is fetched from YouTube first (best effort: if the catalog is not configured, unavailable or out of
quota the list is published without metadata and those fields stay `null`).

Response `201`, `Location: /api/mi-establecimiento/lista-defecto`:

| Field | Type | Notes |
|-------|------|-------|
| `id` | uuid | |
| `nombre` | string | |
| `version` | int | |
| `politicaRotacion` | enum | |
| `creadoAt` | instant | |
| `canciones[]` | array | `posicion` (int), `cancionId` (uuid), `youtubeVideoId`, `titulo`, `artista`, `artistaVerificado`, `canal`, `duracionSegundos`, `miniaturaUrl` |

Captured:

```json
{"id":"<listaId>","nombre":"Noche","version":1,"politicaRotacion":"SECUENCIAL","creadoAt":"<instant>","canciones":[{"posicion":1,"cancionId":"<cancionId-1>","youtubeVideoId":"vidCamisa01","titulo":"La camisa negra","artista":"Juanes","artistaVerificado":false,"canal":"Juanes - Topic","duracionSegundos":227,"miniaturaUrl":"https://img.test/vidCamisa01/mqdefault.jpg"},{"posicion":2,"cancionId":"<cancionId-2>","youtubeVideoId":"vidOjala001","titulo":"Ojalá que llueva café","artista":"JuanLuisGuerra","artistaVerificado":false,"canal":"JuanLuisGuerraVEVO","duracionSegundos":250,"miniaturaUrl":"https://img.test/vidOjala001/mqdefault.jpg"},{"posicion":3,"cancionId":"<cancionId-3>","youtubeVideoId":"vidVivir001","titulo":"Vivir mi vida","artista":"Marc Anthony","artistaVerificado":false,"canal":"Marc Anthony","duracionSegundos":252,"miniaturaUrl":"https://img.test/vidVivir001/mqdefault.jpg"}]}
```

Errors (captured): `400 VALIDATION_ERROR` (`canciones` empty/missing/>200: `must contain between 1 and 200
songs`), `400 INVALID_VIDEO_ID`, `400 DUPLICATE_SONG`, `400 VIDEO_UNAVAILABLE` (YouTube answered that a video
does not exist or cannot be played).
Events: `default_list.versioned`. A session keeps the version it opened with; the next session uses the newest.

#### GET `/api/mi-establecimiento/lista-defecto` - newest version

Response `200`: same shape as above. Errors: `404 NO_DEFAULT_LIST`.

#### GET `/api/mi-establecimiento/listas-defecto` - history

Response `200`: newest first, each `id`, `nombre`, `version`, `politicaRotacion`, `creadoAt`, `totalCanciones`
(no songs). Captured:

```json
[{"id":"<listaId>","nombre":"Noche","version":1,"politicaRotacion":"SECUENCIAL","creadoAt":"<instant>","totalCanciones":3}]
```

### 3.7 Owner: sessions

#### POST `/api/mi-establecimiento/sesiones` - open a session

No body. Response `201`, `Location: /api/mi-establecimiento/sesion-actual`, body `Sesion`. Captured:

```json
{"id":"<sesionId>","establecimientoId":"<establecimientoId>","listaId":"<listaId>","fechaOperativa":"2026-11-12","estado":"ABIERTA","abiertaAt":"<instant>","cerradaAt":null}
```

Errors (captured): `409 ESTABLISHMENT_INACTIVE`, `409 ESTABLISHMENT_IN_MAINTENANCE`, `409 ESTABLISHMENT_PAUSED`,
`409 SESSION_ALREADY_OPEN`, `409 NO_DEFAULT_LIST`. Allowed outside the weekly windows (patrons still cannot
join until a window starts). Events: `session.opened`; SSE push.

#### GET `/api/mi-establecimiento/sesion-actual`

Response `200` `Sesion` (the one in operation: `ABIERTA`, `PAUSADA` or `CERRANDO`). Errors: `404
NO_ACTIVE_SESSION`.

#### POST `/api/mi-establecimiento/sesion/cierre` - close

No body. Response `200`: `sesion` (`Sesion`, now `CERRADA`) and `pendientesAlCierre` (int, requests left
unattended). Captured:

```json
{"sesion":{"id":"<sesionId>","establecimientoId":"<establecimientoId>","listaId":"<listaId>","fechaOperativa":"2026-11-12","estado":"CERRADA","abiertaAt":"<instant>","cerradaAt":"<instant>"},"pendientesAlCierre":0}
```

Effects: waiting/playing requests become `NO_ATENDIDA_CIERRE`, the playing song ends as `INTERRUMPIDA_CIERRE`,
every participant credential is revoked (`401 SESSION_ENDED` from the commit on). Events: `session.closed`
(+ `queue.changed` when the queue changed); SSE push (`estado: CERRADO`, `colaVersion: 0`).
Errors: `404 NO_ACTIVE_SESSION`.

#### POST `/api/mi-establecimiento/sesion/pausa` and `/sesion/reanudacion`

No body. Response `200` `Sesion` with `estado` `PAUSADA` / `ABIERTA`. While paused nobody joins, requests or
votes (`409 SESSION_PAUSED`); participants keep their credentials and keep reading the queue and the stream;
the owner can still drive playback.
Errors (captured): `404 NO_ACTIVE_SESSION`, `409 SESSION_NOT_PAUSABLE` (pause when not `ABIERTA`), `409
SESSION_NOT_PAUSED` (resume when not `PAUSADA`). Events: `session.state_changed`; SSE push (same
`colaVersion`, new `estado`).

### 3.8 Owner: system pause and maintenance

All four take no body and lock the establishment row; events `configuration.changed`; SSE push.

| Route | Response 200 | Errors (captured) |
|-------|--------------|-------------------|
| POST `/api/mi-establecimiento/pausa` | `Establecimiento` (`pausado: true`) | `409 ESTABLISHMENT_ALREADY_PAUSED` |
| POST `/api/mi-establecimiento/reanudacion` | `Establecimiento` (`pausado: false`) | `409 ESTABLISHMENT_NOT_PAUSED` |
| POST `/api/mi-establecimiento/mantenimiento/inicio` | `{ "establecimiento": Establecimiento, "solicitudesFinalizadas": int }` | `409 ALREADY_IN_MAINTENANCE`, `409 SESSION_IN_PROGRESS` |
| POST `/api/mi-establecimiento/mantenimiento/fin` | `Establecimiento` (`enMantenimiento: false`) | `409 NOT_IN_MAINTENANCE` |

- **System pause:** public status `PAUSADO`; joins, new sessions, requests and votes are refused
  (`ESTABLISHMENT_PAUSED`); the session in operation keeps its own state and is never closed because of it.
- **Maintenance** (between operating days, only with no session in operation): public status
  `MANTENIMIENTO`; joins and new sessions refused (`ESTABLISHMENT_IN_MAINTENANCE`). Starting it finalizes
  leftover active requests of closed sessions as `NO_ATENDIDA_CIERRE` (count in `solicitudesFinalizadas`) and
  publishes `maintenance.executed`.

Captured (`mantenimiento/inicio`):

```json
{"establecimiento":{"id":"<establecimientoId>","nombre":"Bar La Esquina","qrSlug":"bar-la-esquina-d40cc0","zonaHoraria":"America/Bogota","activo":true,"pausado":false,"enMantenimiento":true,"creadoAt":"<instant>"},"solicitudesFinalizadas":0}
```

### 3.9 Owner: catalog

#### GET `/api/mi-establecimiento/catalogo/busqueda?q=&pagina=`

Same parameters and response as the participant search, but: no per-owner rate limit, may spend the whole
daily quota, and a missing `q` is `400 INVALID_QUERY` (problem). Captured:

```json
{"resultados":[{"videoId":"vidDespa001","titulo":"Despacito","artista":"LuisFonsi","artistaVerificado":false,"canal":"LuisFonsiVEVO","duracionSegundos":282,"miniaturaUrl":"https://img.test/vidDespa001/mqdefault.jpg"}],"siguientePagina":null}
```

Errors: `400 INVALID_QUERY`, `400 INVALID_PAGE`, `404 NO_ESTABLISHMENT`, `503 CATALOG_NOT_CONFIGURED`, `503
CATALOG_QUOTA_EXCEEDED`, `503 CATALOG_UNAVAILABLE`.

#### GET `/api/mi-establecimiento/catalogo/sugerencias?q=`

Same as the participant suggestions, no rate limit. Captured:

```json
{"sugerencias":[{"tipo":"CANCION","texto":"Ojalá que llueva café","cancion":{"videoId":"vidOjala001","titulo":"Ojalá que llueva café","artista":"JuanLuisGuerra","artistaVerificado":false,"canal":"JuanLuisGuerraVEVO","duracionSegundos":250,"miniaturaUrl":"https://img.test/vidOjala001/mqdefault.jpg"}},{"tipo":"BUSQUEDA","texto":"bohemian rhapsody"},{"tipo":"BUSQUEDA","texto":"bohemian rhapsody queen"},{"tipo":"BUSQUEDA","texto":"bohemian rhapsody lyrics"}]}
```

Errors: `400 INVALID_QUERY`, `404 NO_ESTABLISHMENT`.

### 3.10 Owner: content blocks

#### POST `/api/mi-establecimiento/bloqueos` - block a song

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `videoId` | string | yes | YouTube id |
| `duracionMinutos` | int | exactly one of these two | 1-10080 (one week) |
| `permanente` | boolean | exactly one of these two | `true` |
| `motivoCodigo` | enum | yes | `INAPROPIADO` \| `DERECHOS` \| `OTRO` |

Response `201` `Bloqueo`:

| Field | Type | Notes |
|-------|------|-------|
| `id` | uuid | |
| `videoId` | string | |
| `cancion` | object | `titulo`, `artista`, `miniaturaUrl` (nullable) |
| `motivoCodigo` | string | |
| `desdeAt` | instant | |
| `hastaAt` | instant \| null | null = permanent |
| `permanente` | boolean | |
| `levantadoAt` | instant \| null | set when lifted early |
| `vigente` | boolean | in force now |
| `solicitudesBloqueadas` | int | creation only: waiting requests taken out of the queue (now `BLOQUEADA`) |
| `reproduciendoActualmente` | boolean | creation only: the song is playing now; it is **not** interrupted, skip it if needed |

Captured:

```json
{"id":"<bloqueoId>","videoId":"vidSongD001","cancion":{"titulo":"Titulo vidSongD001","artista":"Canal","miniaturaUrl":"https://img.test/vidSongD001/mqdefault.jpg"},"motivoCodigo":"INAPROPIADO","desdeAt":"<instant>","hastaAt":"<instant +120 min>","permanente":false,"levantadoAt":null,"vigente":true,"solicitudesBloqueadas":1,"reproduciendoActualmente":false}
```

Errors (captured): `400 INVALID_VIDEO_ID`, `400 INVALID_BLOCK_DURATION`, `400 INVALID_BLOCK_REASON`, `409
SONG_ALREADY_BLOCKED`; for a song never stored, `404 SONG_NOT_FOUND`/`409 SONG_UNAVAILABLE`/`503 CATALOG_*`
(derived). Events: `moderation.changed` (+ `queue.changed` when requests were removed); SSE push only when
requests were removed.

#### GET `/api/mi-establecimiento/bloqueos[?todos=true]`

Response `200`: blocks newest first (no `solicitudesBloqueadas`/`reproduciendoActualmente`); only those in force,
or the whole history with `todos=true`.

#### DELETE `/api/mi-establecimiento/bloqueos/{bloqueoId}` - lift early

Response `200` `Bloqueo` with `levantadoAt` set and `vigente: false`. Idempotent: lifting an already lifted or
expired block answers `200` with its current state. Errors: `404 BLOCK_NOT_FOUND` (also for another
establishment's id), `400 INVALID_PARAMETER`. Events: `moderation.changed` (only when it was in force).

### 3.11 Owner: queue and playback driver

All act on the caller's session in operation (`404 NO_ACTIVE_SESSION` when none). See section 5 for the loop.

#### POST `/api/mi-establecimiento/reproduccion/siguiente` - start the next song

No body. Response `200` `InicioReproduccion`:

| Field | Type | Notes |
|-------|------|-------|
| `reproduccionId` | uuid | |
| `origen` | enum | `SOLICITUD` (head of the queue) \| `DEFECTO` (default list) |
| `solicitudId` | uuid \| null | null for `DEFECTO` |
| `cancion` | `CancionCola` | play `https://www.youtube.com/watch?v={videoId}` / the embed player |
| `iniciadaAt` | instant | |
| `votos` | long | votes at start (0 for `DEFECTO`) |
| `colaVersion` | long | |

Captured:

```json
{"reproduccionId":"<reproduccionId-1>","origen":"SOLICITUD","solicitudId":"<solicitudId-A>","cancion":{"videoId":"vidSongA001","titulo":"Titulo vidSongA001","artista":"Canal","artistaVerificado":false,"duracionSegundos":200,"miniaturaUrl":"https://img.test/vidSongA001/mqdefault.jpg"},"iniciadaAt":"<instant>","votos":3,"colaVersion":6}
```

```json
{"reproduccionId":"<reproduccionId-4>","origen":"DEFECTO","solicitudId":null,"cancion":{"videoId":"vidCamisa01","titulo":"La camisa negra","artista":"Juanes","artistaVerificado":false,"duracionSegundos":227,"miniaturaUrl":"https://img.test/vidCamisa01/mqdefault.jpg"},"iniciadaAt":"<instant>","votos":0,"colaVersion":15}
```

Errors (captured): `409 PLAYBACK_IN_PROGRESS`, `409 QUEUE_EMPTY`, `404 NO_ACTIVE_SESSION`.
Events: `playback.started`, `playback.source_changed` (when the origin differs from the previous playback),
`queue.changed`; SSE push.

#### POST `/api/mi-establecimiento/reproduccion/fin` - report how the playing song ended

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `resultado` | enum | yes | `COMPLETADA` \| `SALTADA` \| `FALLIDA` |
| `motivoCodigo` | string | only with `SALTADA` | `SALTO_MANUAL` (default) \| `CONTENIDO_INAPROPIADO` \| `CONTENIDO_BLOQUEADO` \| `PROBLEMA_TECNICO` |

Response `200` `FinReproduccion`: `reproduccionId`, `solicitudId` (null for `DEFECTO`), `resultado`,
`motivoCodigo` (null unless `SALTADA`), `terminadaAt`, `colaVersion`. **Does not start the next song.** Captured:

```json
{"reproduccionId":"<reproduccionId-1>","solicitudId":"<solicitudId-A>","resultado":"COMPLETADA","motivoCodigo":null,"terminadaAt":"<instant>","colaVersion":7}
{"reproduccionId":"<reproduccionId-3>","solicitudId":"<solicitudId-C>","resultado":"SALTADA","motivoCodigo":"CONTENIDO_INAPROPIADO","terminadaAt":"<instant>","colaVersion":10}
```

The request becomes `ATENDIDA` / `SALTADA` / `FALLIDA`. Errors (captured): `400 VALIDATION_ERROR` (missing
`resultado`), `400 INVALID_BODY` (unknown `resultado`), `400 SKIP_REASON_NOT_ALLOWED`, `400
INVALID_SKIP_REASON`, `409 NO_ACTIVE_PLAYBACK`. Events: `playback.finished`, `queue.changed`; SSE push.

#### POST `/api/mi-establecimiento/reproduccion/saltar` - skip and start the next one

Optional body `{"motivoCodigo": "..."}` (same values, default `SALTO_MANUAL`). Ends the playing song as
`SALTADA` and starts the next one in the same transaction. Response `200`: `fin` (`FinReproduccion`) and
`siguiente` (`InicioReproduccion` \| null when nothing is left to play; the skip still succeeds). Captured:

```json
{"fin":{"reproduccionId":"<reproduccionId-2>","solicitudId":"<solicitudId-B>","resultado":"SALTADA","motivoCodigo":"PROBLEMA_TECNICO","terminadaAt":"<instant>","colaVersion":9},"siguiente":{"reproduccionId":"<reproduccionId-3>","origen":"SOLICITUD","solicitudId":"<solicitudId-C>","cancion":{"videoId":"vidSongC001","titulo":"Titulo vidSongC001","artista":"Canal","artistaVerificado":false,"duracionSegundos":200,"miniaturaUrl":"https://img.test/vidSongC001/mqdefault.jpg"},"iniciadaAt":"<instant>","votos":1,"colaVersion":9}}
```

Errors (captured): `400 INVALID_SKIP_REASON`, `409 NO_ACTIVE_PLAYBACK`. Events: `playback.finished`,
`playback.started` (when something follows), audit `PLAYBACK_SKIPPED`; SSE push.

#### DELETE `/api/mi-establecimiento/cola/{solicitudId}` - remove a waiting request

Response `200`: `solicitudId`, `estado` (`SALTADA`), `motivoCodigo` (`ELIMINADA_POR_PERSONAL`), `colaVersion`.
The request is never deleted; its votes stay. Captured:

```json
{"solicitudId":"<solicitudId-E>","estado":"SALTADA","motivoCodigo":"ELIMINADA_POR_PERSONAL","colaVersion":12}
```

Errors (captured): `400 INVALID_PARAMETER`, `404 REQUEST_NOT_FOUND` (unknown or another session's),
`409 REQUEST_NOT_ACTIVE` (playing or finished: skip the playing one instead).
Events: `request.removed`, `queue.changed`; SSE push.

### 3.12 Health

| Route | Who | Response |
|-------|-----|----------|
| GET `/actuator/health` | public | `200 {"groups":["liveness","readiness"],"status":"UP"}` (captured, `application/vnd.spring-boot.actuator.v3+json`); `/actuator/health/liveness` and `/readiness` also public |
| GET `/actuator/info` | public | Spring Boot info (not captured) |

### 3.13 Route summary (41 routes)

| # | Method | Path | Who |
|---|--------|------|-----|
| 1 | GET | `/api/public/establecimientos/{qrSlug}` | public |
| 2 | GET | `/api/public/politica` | public |
| 3 | POST | `/api/public/establecimientos/{qrSlug}/participaciones` | public |
| 4 | GET | `/api/public/establecimientos/{qrSlug}/cola` | public |
| 5 | GET | `/api/public/establecimientos/{qrSlug}/cola/eventos` | public (SSE) |
| 6 | GET | `/api/participante/sesion` | participant |
| 7 | DELETE | `/api/participante/sesion` | participant |
| 8 | GET | `/api/participante/catalogo/busqueda` | participant |
| 9 | GET | `/api/participante/catalogo/sugerencias` | participant |
| 10 | GET | `/api/participante/cola` | participant |
| 11 | POST | `/api/participante/solicitudes` | participant |
| 12 | POST | `/api/participante/solicitudes/{solicitudId}/votos` | participant |
| 13 | POST | `/api/mi-establecimiento` | owner (no establishment yet) |
| 14 | GET | `/api/mi-establecimiento` | owner |
| 15 | PATCH | `/api/mi-establecimiento` | owner |
| 16 | GET | `/api/mi-establecimiento/qr` | owner |
| 17 | GET | `/api/mi-establecimiento/horarios` | owner |
| 18 | PUT | `/api/mi-establecimiento/horarios` | owner |
| 19 | PUT | `/api/mi-establecimiento/lista-defecto` | owner |
| 20 | GET | `/api/mi-establecimiento/lista-defecto` | owner |
| 21 | GET | `/api/mi-establecimiento/listas-defecto` | owner |
| 22 | POST | `/api/mi-establecimiento/sesiones` | owner |
| 23 | GET | `/api/mi-establecimiento/sesion-actual` | owner |
| 24 | POST | `/api/mi-establecimiento/sesion/cierre` | owner |
| 25 | POST | `/api/mi-establecimiento/sesion/pausa` | owner |
| 26 | POST | `/api/mi-establecimiento/sesion/reanudacion` | owner |
| 27 | POST | `/api/mi-establecimiento/pausa` | owner |
| 28 | POST | `/api/mi-establecimiento/reanudacion` | owner |
| 29 | POST | `/api/mi-establecimiento/mantenimiento/inicio` | owner |
| 30 | POST | `/api/mi-establecimiento/mantenimiento/fin` | owner |
| 31 | GET | `/api/mi-establecimiento/catalogo/busqueda` | owner |
| 32 | GET | `/api/mi-establecimiento/catalogo/sugerencias` | owner |
| 33 | POST | `/api/mi-establecimiento/bloqueos` | owner |
| 34 | GET | `/api/mi-establecimiento/bloqueos` | owner |
| 35 | DELETE | `/api/mi-establecimiento/bloqueos/{bloqueoId}` | owner |
| 36 | POST | `/api/mi-establecimiento/reproduccion/siguiente` | owner |
| 37 | POST | `/api/mi-establecimiento/reproduccion/fin` | owner |
| 38 | POST | `/api/mi-establecimiento/reproduccion/saltar` | owner |
| 39 | DELETE | `/api/mi-establecimiento/cola/{solicitudId}` | owner |
| 40 | GET | `/actuator/health` | public |
| 41 | GET | `/actuator/info` | public |

---

## 4. Real time (SSE)

### 4.1 Wire format

`GET /api/public/establecimientos/{qrSlug}/cola/eventos`, no credential. Response headers (captured):
`200`, `content-type: text/event-stream`, `cache-control: no-cache`, `x-accel-buffering: no`, chunked.

Captured stream (the client sent `Last-Event-ID: 999`; a request was then created; heartbeat shortened for the
capture; captured before the preview fields existed, so current `data:` lines also end with
`"proximasDefecto":[...],"rotacionDefecto":...` exactly as `GET .../cola`):

```
event:cola
id:4
data:{"colaVersion":4,"estado":"ABIERTO","enReproduccion":null,"cola":[{"solicitudId":"<solicitudId-A>","posicion":1,"votos":3,"cancion":{...},"esperaEstimadaSegundos":0},{"solicitudId":"<solicitudId-B>","posicion":2,"votos":1,"cancion":{...},"esperaEstimadaSegundos":200}]}

event:cola
id:5
data:{"colaVersion":5,"estado":"ABIERTO","enReproduccion":null,"cola":[...,{"solicitudId":"<solicitudId-C>","posicion":3,"votos":1,"cancion":{...},"esperaEstimadaSegundos":400}]}

: keep-alive

: keep-alive

```

| Element | Value |
|---------|-------|
| Event name | always `cola` |
| `id:` | the snapshot's `colaVersion` |
| `data:` | one line: the **full public snapshot**, byte-identical in shape to `GET .../cola` (no `votado`), including `proximasDefecto` and `rotacionDefecto` |
| Keep-alive | comment line `: keep-alive` every 15 s (`app.cola.sse-heartbeat-millis`) |
| First event | sent immediately on connect: the current snapshot |
| `Last-Event-ID` | ignored on purpose (the first event is always a full snapshot) |
| Max lifetime | 30 min (`app.cola.sse-timeout-minutes`), then the server completes the stream: reconnect |
| Caps | 500 streams per establishment, 10 per client address -> `429 TOO_MANY_STREAMS` (problem JSON, before the stream starts) |
| Errors before streaming | `404 ESTABLISHMENT_NOT_FOUND`, `429 TOO_MANY_STREAMS`, `503 SHUTTING_DOWN` |
| Server restart | every stream is completed; reconnect |

Captured `429` (the per-address cap was lowered to 3 for the capture; the default text says `at most 10`):

```json
{"detail":"Too many open queue streams from this device: at most 3","instance":"/api/public/establecimientos/bar-la-esquina-d40cc0/cola/eventos","status":429,"title":"Too Many Requests","code":"TOO_MANY_STREAMS"}
```

### 4.2 When a snapshot is pushed

After the commit of: a request, a vote, playback start/end/skip, a removal from the queue, a block that removed
requests, session open/close/pause/resume, system pause/resume, maintenance start/end.

**Not pushed:** changes caused only by time (a weekly window starting or ending; the scheduled close does push,
because it closes the session), replacing the weekly schedule, `PATCH` of the establishment (including
`activo`), the estimated waits ticking down, a participant joining or leaving, a timed block expiring (the
blocked song comes back in `proximasDefecto` only on the next snapshot; lifting a block early does push).

### 4.3 Client semantics

1. Every event is a complete snapshot: **replace** your state, never merge.
2. The server never sends the same JSON twice in a row to one stream, but slow clients may skip intermediate
   snapshots (latest wins). That is fine.
3. Ordering: within one stream events arrive in commit order. `colaVersion` only grows **within one session**;
   state-only changes (pause, maintenance, open/close) push a new snapshot with the **same** `colaVersion`, and
   a closed session is `colaVersion: 0` (a new session restarts from 0). So: never drop an event just because its
   `colaVersion` is equal or lower; only use it to drop an out-of-order **REST** response older than the last
   event of the same session.
4. On disconnect, error or the 30-minute completion: reconnect with exponential backoff (e.g. 1 s, 2 s, 4 s ...
   max 30 s, with jitter), and after reconnecting use the first event (or a `GET .../cola`) as the fresh state.
5. On `429 TOO_MANY_STREAMS`, close other tabs/streams and retry after a longer delay (>= 30 s).
6. Because time-driven changes are not pushed, re-fetch `GET /api/public/establecimientos/{qrSlug}` when
   `proximaApertura` is reached and periodically (e.g. every 60 s) while the screen is open.
7. Estimated waits are computed at snapshot time; count `restanteSegundos` down locally from the receipt time.

### 4.4 Getting `votado` (participant app)

The stream is public and carries no `votado`. On each `cola` event, call `GET /api/participante/cola` with the
`pt_` token and render that response (it also proves the session is still alive: `401 SESSION_ENDED` there
is the end of the session). Debounce bursts (e.g. at most one call per 500 ms). A failed stream is not a reason
to stop polling `GET /api/participante/sesion` occasionally (e.g. every 30-60 s) to detect the end.

### 4.5 Consuming it

**Android (OkHttp SSE)**

```kotlin
val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build() // keep-alive every 15 s
val request = Request.Builder()
    .url("$baseUrl/api/public/establecimientos/$slug/cola/eventos")
    .header("Accept", "text/event-stream")
    .build()
EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
    override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
        if (type == "cola") { publicQueue.value = json.decodeFromString<ColaResponse>(data); refreshMyQueue() }
    }
    override fun onClosed(es: EventSource) = scheduleReconnect()                 // 30-minute end
    override fun onFailure(es: EventSource, t: Throwable?, r: Response?) = scheduleReconnect(r?.code)
})
```

Ktor works the same with `install(SSE)` and `client.sse(url) { incoming.collect { ... } }`; handle the stream
completion as a reconnect. Stop the stream when the app goes to the background and reconnect on resume.

**Next.js**

- Browser: `new EventSource(url)` works without headers because the endpoint is public, **but** only from an
  origin listed in `CORS_ALLOWED_ORIGINS` (the browser sends `Origin`, and Spring's CORS processing rejects
  disallowed origins with `403`; derived from the CORS configuration, only the preflight was captured). Listen with `es.addEventListener("cola", e => setQueue(JSON.parse(e.data)))` (the event is named, so
  `onmessage` does not fire). The browser reconnects by itself after errors and after the 30-minute end, sending
  `Last-Event-ID` (ignored, harmless).
- Server side (BFF/route handler proxying the stream): `fetch(url, { headers: { Accept: "text/event-stream" } })`
  and pipe `res.body` to the client response with `Content-Type: text/event-stream`, no compression and no
  buffering; or read it with an SSE parser (`eventsource-parser`). Close the upstream when the client
  disconnects.

---

## 5. Queue and playback semantics

### 5.1 Ordering

The queue is derived on every read, never stored: waiting requests (`PENDIENTE`, `RESERVADA` first) ordered by
**votes desc, creation time asc, id asc**. The playing request is not part of `cola`; popularity never interrupts
the current song. `posicion` is 1-based and can change with every vote.

### 5.2 Requests and votes

- Requesting a song creates a request **with the requester's first vote**; requesting a song that already has
  an active request (`PENDIENTE`, `RESERVADA`, `REPRODUCIENDO`) is a vote on that request (`200`, `creada:false`).
- **One vote per participation per song per session**, across cycles; votes cannot be changed or withdrawn
  (`409 ALREADY_VOTED`). Use `votado` to disable the vote button.
- **Re-request rule:** a song cannot be requested again until 30 minutes after its latest request finished
  (`app.cola.re-request-minutes`; `409 SONG_RECENTLY_PLAYED`). After that a new request (next cycle) can be
  created, but only by participants who never supported that song in this session.
- Blocked songs (`409 SONG_BLOCKED`) and songs YouTube reports unplayable (`409 SONG_UNAVAILABLE`) can be neither
  requested nor voted.

### 5.3 Estimated wait

```
esperaEstimadaSegundos(item i) = restante(playing) + sum(duracionOperativa(item j) for j ahead of i)
restante(playing) = max(0, duracionOperativa(playing) - seconds since iniciadaAt)   (0 when nothing plays)
duracionOperativa = the song's operating duration, 240 s when missing or not positive
```

The operating duration starts as the provider duration (`duracionSegundos`) when the catalog stores or refreshes
the song and YouTube gives one, otherwise 240 s; a refresh only replaces the untouched 240 s value, never one
that was set to something else. Songs stored before this rule keep 240 s until their metadata is next refreshed.
`proximasDefecto[]` waits follow the same formula, continuing after the last request. It is an estimate: votes
reorder the queue at any time, so do not show it as exact.

### 5.4 What plays now (`enReproduccion`)

`origen: SOLICITUD` = a request (has `solicitudId`); `origen: DEFECTO` = a default-list song (`solicitudId:
null`, 0 votes, no request is created). `null` = nothing plays.

### 5.5 Default list rotation

When `siguiente`/`saltar` finds no waiting request (ignoring blocked songs), it takes the next playable item of the
default-list **version pinned by the session** (blocked and unavailable songs skipped):

- `SECUENCIAL`: the first playable item after the last default item played in this session, wrapping to the
  first; the first item when none played yet.
- `ALEATORIA`: a random playable item other than the last played song (repeats only when it is the only one).

The queue snapshots preview this choice in `proximasDefecto` (same rule, so it never disagrees with the player);
an `ALEATORIA` list has an empty preview.

A request that arrives while a default song plays never interrupts it; it plays at the next `siguiente`. When
neither a request nor a playable list item exists: `409 QUEUE_EMPTY`.

### 5.6 Nothing advances automatically

The server has no timer for songs. The web player station (owner token) must drive it:

```
loop:
  r = POST /reproduccion/siguiente
      200 -> play r.cancion.videoId (remember r.reproduccionId)
      409 PLAYBACK_IN_PROGRESS -> GET /api/public/establecimientos/{slug}/cola, resume enReproduccion
                                  (or POST /fin {"resultado":"FALLIDA"} if it cannot be resumed), then continue
      409 QUEUE_EMPTY -> wait (e.g. 10 s, or until an SSE event shows cola non-empty), continue
      404 NO_ACTIVE_SESSION -> stop the loop
  when the video ends normally:   POST /reproduccion/fin {"resultado":"COMPLETADA"}
  when the player errors:         POST /reproduccion/fin {"resultado":"FALLIDA"}
  when the operator skips:        POST /reproduccion/saltar {"motivoCodigo":"SALTO_MANUAL"}
                                  -> play response.siguiente (null: go to loop and handle QUEUE_EMPTY)
  after fin: 409 NO_ACTIVE_PLAYBACK means it was already ended (e.g. by another tab): continue with siguiente
```

There is no station lease: two browser tabs driving the same establishment will fight. Playback calls also work
while the session or the establishment is paused (they only need a session in operation).

### 5.7 Request states visible to clients

| State | Visible as | Reached by |
|-------|------------|------------|
| `PENDIENTE` | item in `cola` | created |
| `RESERVADA` | item at the head of `cola` | reserved for the player (no endpoint sets it on this commit) |
| `REPRODUCIENDO` | `enReproduccion` (`origen: SOLICITUD`) | `siguiente` / `saltar` |
| `ATENDIDA` | gone | `fin COMPLETADA` |
| `SALTADA` | gone | `fin SALTADA`, `saltar`, owner removal (`ELIMINADA_POR_PERSONAL`) |
| `FALLIDA` | gone | `fin FALLIDA` |
| `BLOQUEADA` | gone | the song was blocked while waiting |
| `NO_ATENDIDA_CIERRE` | gone | session closed (or maintenance) while active |

```
PENDIENTE --siguiente/saltar--> REPRODUCIENDO --fin COMPLETADA--> ATENDIDA
    |                                 |--fin SALTADA / saltar--> SALTADA
    |                                 |--fin FALLIDA----------> FALLIDA
    |                                 '--session close--------> NO_ATENDIDA_CIERRE
    |--owner DELETE /cola/{id}--> SALTADA
    |--song blocked-------------> BLOQUEADA
    '--session close------------> NO_ATENDIDA_CIERRE
```

Session states: `ABIERTA <-> PAUSADA` (owner), `-> CERRADA` (owner close or scheduled close). `CERRANDO` exists
in the contract and counts as "in operation but closed to new participants" (`CERRADO` publicly); no endpoint
sets it on this commit.

---

## 6. Operational states and flows

### 6.1 What a patron sees and can do

Precedence top to bottom: the first matching row wins.

| Condition | `servicio` (status / public `estado`) | Join | Request / vote (already inside) | Read queue + SSE |
|-----------|--------------------------------------|------|----------------------------------|------------------|
| Establishment inactive (`activo:false`) | `CERRADO` | 409 `ESTABLISHMENT_INACTIVE` | no session can exist | public: empty `CERRADO` |
| Maintenance | `MANTENIMIENTO` | 409 `ESTABLISHMENT_IN_MAINTENANCE` | no session can exist | yes |
| System pause | `PAUSADO` | 409 `ESTABLISHMENT_PAUSED` | 409 `ESTABLISHMENT_PAUSED` | yes |
| Windows exist, now outside all | `CERRADO` + `proximaApertura` | 409 `OUTSIDE_SCHEDULE` (even with an open session) | allowed if the session is `ABIERTA` (until it closes) | yes |
| No session in operation | `CERRADO` | 409 `NO_ACTIVE_SESSION` | n/a (`401 SESSION_ENDED`) | public: empty `CERRADO` |
| Session `PAUSADA` | `PAUSADO` | 409 `SESSION_PAUSED` | 409 `SESSION_PAUSED` | yes |
| Session `ABIERTA` | `ABIERTO` | 201 | allowed | yes |

Scheduled close (every 30 s): a session in operation is closed automatically when the establishment has at
least one window, is now outside all of them, the session was **opened inside a window** and has been open at
least 60 s. A session the owner opened outside every window is never closed automatically. The rule uses the
current schedule, so replacing the windows while a session is open takes effect on the next tick. Pauses
neither cause nor prevent it.

### 6.2 Owner onboarding

```
1. Register / log in on FusionAuth (authorization code + PKCE, scopes openid offline_access)
2. GET  /api/mi-establecimiento               -> 404 NO_ESTABLISHMENT means "show the create form"
3. POST /api/mi-establecimiento               {nombre, qrSlug?, zonaHoraria?}
4. PUT  /api/mi-establecimiento/horarios      [ {diaSemana, inicio, fin}, ... ]   (optional; [] = always open)
5. PUT  /api/mi-establecimiento/lista-defecto {canciones:[videoId...]}             (required before opening)
6. GET  /api/mi-establecimiento/qr            -> print svg (it never changes)
7. Each night: POST /sesiones -> player loop (5.6) -> POST /sesion/cierre (or the scheduled close)
8. Between nights (optional): POST /mantenimiento/inicio ... /mantenimiento/fin
```

```bash
AT=<access token>
API=http://localhost:8080
curl -s -X POST $API/api/mi-establecimiento -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' \
  -d '{"nombre":"Bar La Esquina"}'
curl -s -X PUT $API/api/mi-establecimiento/horarios -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' \
  -d '[{"diaSemana":5,"inicio":"20:00","fin":"02:00"},{"diaSemana":6,"inicio":"20:00","fin":"02:00"}]'
curl -s -X PUT $API/api/mi-establecimiento/lista-defecto -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' \
  -d '{"nombre":"Noche","politicaRotacion":"SECUENCIAL","canciones":["vidCamisa01","vidOjala001","vidVivir001"]}'
curl -s -X POST $API/api/mi-establecimiento/sesiones -H "Authorization: Bearer $AT"
curl -s -X POST $API/api/mi-establecimiento/reproduccion/siguiente -H "Authorization: Bearer $AT"
curl -s -X POST $API/api/mi-establecimiento/reproduccion/fin -H "Authorization: Bearer $AT" \
  -H 'Content-Type: application/json' -d '{"resultado":"COMPLETADA"}'
curl -s -X POST $API/api/mi-establecimiento/sesion/cierre -H "Authorization: Bearer $AT"
```

Patron flow:

```bash
SLUG=bar-la-esquina-d40cc0
curl -s $API/api/public/establecimientos/$SLUG
curl -s $API/api/public/politica
PT=$(curl -s -X POST $API/api/public/establecimientos/$SLUG/participaciones -H 'Content-Type: application/json' \
  -d '{"canal":"WEB","politicaVersion":"1.0","autorizaF1":true}' | jq -r .token)
curl -s "$API/api/participante/catalogo/sugerencias?q=la%20cam" -H "Authorization: Bearer $PT"
curl -s -X POST $API/api/participante/solicitudes -H "Authorization: Bearer $PT" \
  -H 'Content-Type: application/json' -d '{"videoId":"vidCamisa01"}'
curl -s $API/api/participante/cola -H "Authorization: Bearer $PT"
curl -N $API/api/public/establecimientos/$SLUG/cola/eventos
```

### 6.3 QR and deep link contract

- The QR encodes `{PUBLIC_BASE_URL}/s/{qrSlug}` (`app.public-base-url`, env `PUBLIC_BASE_URL`, default
  `http://localhost:3000`; trailing slashes removed). It identifies the establishment, not the night, and never
  changes (the slug is immutable).
- **Web:** the Next.js app must serve `/s/[slug]` and start the join flow with that slug (status -> policy ->
  consent -> join).
- **Android:** register an App Link (`autoVerify`) for `https://<public host>/s/*` and, in the in-app scanner,
  accept any scanned text whose path matches `/s/{slug}` (take the last path segment; slugs are
  `[a-z0-9-]{3,80}`). Reject other content.
- Anyone with the URL can join while a session is open and inside a window (no proof of presence); the join
  limits and the credential dying at close are the mitigations.

---

## 7. Client rules and gotchas

### 7.1 Both clients

- [ ] Branch on `code`, never on `detail`. Unknown codes: show a generic error and log the code.
- [ ] Unknown `servicio`/`estado` values (new ones may be added) = "cannot participate".
- [ ] Parse instants as UTC instants and render in the establishment's zone; durations are seconds.
- [ ] `401 SESSION_ENDED` is terminal for that `pt_` token: clear it and the session UI; keep the `dv_` token.
- [ ] Always send the stored `dv_` token when joining; always overwrite it when a join returns a new one.
- [ ] Never log or send tokens to analytics; do not put them in URLs.
- [ ] Treat `409 ALREADY_VOTED` after a retry as success; refetch the queue after every request/vote.
- [ ] YouTube quota: a search costs 100 units of a 10,000/day project quota; participants of one establishment
      share ~24 uncached searches per day (`SEARCH_BUDGET_EXHAUSTED`, 429) and each participant 6 searches/min.
      Suggestions are free (60/min): **prefer suggestions**, search only on explicit submit (never per
      keystroke), debounce suggestions (~250-300 ms) and require 2+ characters.
- [ ] `SEARCH_BUDGET_EXHAUSTED` and `CATALOG_QUOTA_EXCEEDED`: hide/disable search with a friendly message and
      keep suggestions and voting working.

### 7.2 Android (patron app)

- [ ] Cleartext HTTP only in dev builds (`network_security_config` debug override): emulator host `10.0.2.2`,
      physical device = the dev machine's LAN IP. Release builds use HTTPS only.
- [ ] Store the `dv_` token in Keystore-backed storage (EncryptedSharedPreferences / DataStore with Tink); keep
      the `pt_` token in memory or the same encrypted storage, delete it on `SESSION_ENDED`/`INVALID_CREDENTIAL`.
- [ ] Join with `"canal":"ANDROID"`.
- [ ] SSE: OkHttp read timeout 0 (or > 15 s); reconnect on close/failure with backoff; stop in background.
- [ ] Handle the deep link `/s/{slug}` (App Link) and scanned QR text the same way.
- [ ] On `INVALID_CREDENTIAL` while still at the venue, rejoin silently with the `dv_` token (same pseudonym).

### 7.3 Next.js (admin panel, player station and web patron flow)

- [ ] Prefer calling the API from the Next.js server (BFF: route handlers / server actions) with the access
      token kept server-side (Auth.js session); then CORS and token exposure do not apply. If the browser calls
      the API directly, its origin must be in `CORS_ALLOWED_ORIGINS` and only `Authorization`, `Content-Type`,
      `Idempotency-Key` headers may be sent (no tracing headers).
- [ ] Send the **access token** (Auth.js `account.access_token`), never the ID token (`401`, `tty` check).
- [ ] Refresh the access token before its 15-minute expiry using the refresh token (`offline_access`); on a
      `401` refresh once, then re-login.
- [ ] `GET /api/mi-establecimiento` -> `404 NO_ESTABLISHMENT` is the "create your establishment" state, not an error.
- [ ] Serve `/s/[slug]` for the web patron flow (`canal: "WEB"`).
- [ ] Only one player tab per establishment; follow the loop in 5.6; on `409 PLAYBACK_IN_PROGRESS` reconcile
      from the queue instead of retrying `siguiente`.
- [ ] Re-fetch the establishment status on a timer: schedule boundaries and `PATCH activo` are not pushed.
- [ ] Owner 401/403 have an empty body: do not try to parse JSON on them.

---

## 8. Error catalog

| Code | HTTP | Endpoint(s) | Client action |
|------|------|-------------|---------------|
| `VALIDATION_ERROR` | 400 | any with a validated body | Show field errors from `errors[]`. |
| `INVALID_BODY` | 400 | any with a body | Fix the request (JSON, types, enum values). |
| `INVALID_PARAMETER` | 400 | path/query ids | Fix the id. |
| `INVALID_QUERY` | 400 | catalog search, suggestions | Require 2-100 characters. |
| `INVALID_PAGE` | 400 | catalog search | Restart from the first page. |
| `INVALID_VIDEO_ID` | 400 | solicitudes, lista-defecto, bloqueos | Use a `videoId` from search/suggestions. |
| `VIDEO_UNAVAILABLE` | 400 | PUT lista-defecto | Remove that video from the list. |
| `DUPLICATE_SONG` | 400 | PUT lista-defecto | Remove the duplicate. |
| `CONSENT_REQUIRED` | 400 | join | Ask for F1 consent. |
| `EMPTY_UPDATE` | 400 | PATCH establishment | Send at least one field. |
| `INVALID_TIME_ZONE` | 400 | POST/PATCH establishment | Pick an IANA zone. |
| `INVALID_SCHEDULE` | 400 | PUT horarios | Show `detail`, fix the window. |
| `SCHEDULE_OVERLAP` | 400 | PUT horarios | Fix the overlapping windows. |
| `TOO_MANY_WINDOWS` | 400 | PUT horarios | At most 21 windows. |
| `INVALID_BLOCK_DURATION` | 400 | POST bloqueos | Exactly one of `duracionMinutos` (1-10080) or `permanente:true`. |
| `INVALID_BLOCK_REASON` | 400 | POST bloqueos | `INAPROPIADO`/`DERECHOS`/`OTRO`. |
| `SKIP_REASON_NOT_ALLOWED` | 400 | reproduccion/fin | Send `motivoCodigo` only with `SALTADA`. |
| `INVALID_SKIP_REASON` | 400 | reproduccion/fin, saltar | Use a valid skip reason. |
| `INVALID_TOKEN` | 401 | owner routes | Token without usable `sub`: re-login. |
| `INVALID_CREDENTIAL` | 401 | participant routes | Drop `pt_`; rejoin with `dv_` or rescan. |
| `SESSION_ENDED` | 401 | participant routes | Terminal: clear session data, go to scan. |
| (empty body) | 401 | owner routes | Refresh the access token once, then re-login. |
| (empty body) | 403 | unlisted routes, other actuator endpoints | Programming error. |
| `NO_ESTABLISHMENT` | 404 | every owner route except POST | Show the create-establishment flow. |
| `ESTABLISHMENT_NOT_FOUND` | 404 | public routes by slug | Bad/old QR: show "not found", rescan. |
| `NO_ACTIVE_SESSION` | 404 | owner session/playback/queue routes | No session: offer "open session". |
| `NO_ACTIVE_SESSION` | 409 | join | Show "closed"; refresh status later. |
| `NO_DEFAULT_LIST` | 404 | GET lista-defecto | Offer to create the list. |
| `NO_DEFAULT_LIST` | 409 | POST sesiones | Create the default list first. |
| `REQUEST_NOT_FOUND` | 404 | votos, DELETE cola | Refetch the queue. |
| `SONG_NOT_FOUND` | 404 | solicitudes, bloqueos | Pick another song. |
| `BLOCK_NOT_FOUND` | 404 | DELETE bloqueos | Refetch blocks. |
| `SESSION_NOT_FOUND` | 404 | playback (race, practically unreachable) | Refetch the session. |
| `POLICY_VERSION_MISMATCH` | 409 | join | Refetch the policy, ask consent again. |
| `ESTABLISHMENT_INACTIVE` | 409 | join, POST sesiones | Show "closed". |
| `ESTABLISHMENT_PAUSED` | 409 | join, solicitudes, votos, POST sesiones | Show "paused", retry later. |
| `ESTABLISHMENT_IN_MAINTENANCE` | 409 | join, solicitudes, votos, POST sesiones | Show "maintenance". |
| `OUTSIDE_SCHEDULE` | 409 | join | Show `proximaApertura` from the status endpoint. |
| `SESSION_PAUSED` | 409 | join, solicitudes, votos | Show "paused", keep listening to SSE. |
| `SESSION_NOT_ACCEPTING` | 409 | solicitudes, votos | Session closing: stop actions. |
| `SESSION_FULL` | 409 | join | Show "full". |
| `SESSION_ALREADY_OPEN` | 409 | POST sesiones | Read `sesion-actual`. |
| `SESSION_ALREADY_CLOSED` | 409 | close (race, practically unreachable) | Refetch. |
| `SESSION_IN_PROGRESS` | 409 | PATCH `activo:false`, mantenimiento/inicio | Close the session first. |
| `SESSION_NOT_PAUSABLE` | 409 | sesion/pausa | Already paused/closing: refetch. |
| `SESSION_NOT_PAUSED` | 409 | sesion/reanudacion | Already open: refetch. |
| `SESSION_NOT_IN_OPERATION` | 409 | playback (race) | Refetch the session. |
| `ESTABLISHMENT_ALREADY_EXISTS` | 409 | POST establishment | Use GET instead. |
| `ESTABLISHMENT_ALREADY_PAUSED` | 409 | pausa | Treat as applied. |
| `ESTABLISHMENT_NOT_PAUSED` | 409 | reanudacion | Treat as applied. |
| `ALREADY_IN_MAINTENANCE` | 409 | mantenimiento/inicio | Treat as applied. |
| `NOT_IN_MAINTENANCE` | 409 | mantenimiento/fin | Treat as applied. |
| `SLUG_TAKEN` | 409 | POST establishment | Choose another `qrSlug` (or omit it). |
| `ALREADY_VOTED` | 409 | solicitudes, votos | Mark `votado`; not an error for the user. |
| `REQUEST_NOT_ACTIVE` | 409 | votos, DELETE cola | Refetch the queue. |
| `REQUEST_CONFLICT` | 409 | solicitudes (backstop) | Retry once. |
| `QUEUE_FULL` | 409 | solicitudes | Suggest voting an existing song. |
| `SONG_RECENTLY_PLAYED` | 409 | solicitudes | "Played less than 30 min ago". |
| `SONG_BLOCKED` | 409 | solicitudes, votos | "Not available here". |
| `SONG_UNAVAILABLE` | 409 | solicitudes, votos, bloqueos | "Cannot be played here". |
| `SONG_ALREADY_BLOCKED` | 409 | POST bloqueos | Lift the existing block first. |
| `PLAYBACK_IN_PROGRESS` | 409 | siguiente | Reconcile from `enReproduccion`. |
| `QUEUE_EMPTY` | 409 | siguiente | Wait for requests or fix the default list. |
| `NO_ACTIVE_PLAYBACK` | 409 | fin, saltar | Already ended: call `siguiente`. |
| `RATE_LIMITED` | 429 | join, search, suggestions, solicitudes, votos | Back off >= until the next minute. |
| `TOO_MANY_STREAMS` | 429 | SSE | Close other streams; retry after >= 30 s. |
| `SEARCH_BUDGET_EXHAUSTED` | 429 | participant search; solicitudes of a never-stored song | Disable search for today; keep suggestions. |
| `CATALOG_QUOTA_EXCEEDED` | 503 | searches; lookups of never-stored songs | Disable search until tomorrow (Pacific midnight). |
| `CATALOG_UNAVAILABLE` | 503 | same | Retry later with backoff. |
| `CATALOG_NOT_CONFIGURED` | 503 | same | Server has no YouTube key (dev): use suggestions/stored songs. |
| `JOIN_UNAVAILABLE` | 503 | join | Retry after ~1 s. |
| `SHUTTING_DOWN` | 503 | SSE | Reconnect with backoff. |

---

## 9. Known quirks

1. **Not every error is a problem detail.** Owner-token 401 and the 403 of unlisted routes have an empty body
   (reason only in `WWW-Authenticate`). Unknown routes (`404`), wrong method (`405`), wrong content type (`415`)
   and a **missing `q` on `GET /api/participante/catalogo/busqueda`** answer Spring's default
   `{"timestamp","status","error","path"}` as `application/json`, without `code`. The owner search without `q`
   instead answers `400 INVALID_QUERY`, and the participant suggestions without `q` also answer
   `INVALID_QUERY`.
2. **`type` is inconsistent:** problems written by the participant security filter include `"type":"about:blank"`;
   problems from the exception handler omit `type`.
3. **Tomcat rejects encoded `%00` and `%2F` in paths** with an HTML `400` page (`text/html`) before Spring runs
   (captured for `/api/public/establecimientos/%00` and `/a%2Fb`). URL-encode slugs only with the slug alphabet
   in mind (it never needs encoding).
4. **`PATCH /api/mi-establecimiento` ignores unknown fields** (including `qrSlug`) and returns `200` even when
   nothing changed; it only fails with `EMPTY_UPDATE` when all three known fields are absent or null.
5. **Outside the schedule, patrons already inside keep requesting and voting** while the session is `ABIERTA`
   (only joining is blocked), until the scheduled close runs (if the session was opened inside a window).
6. **`proximaApertura` only appears when the schedule is the reason for `CERRADO`.** With no session but inside
   a window it is absent; with inactive/paused/maintenance it is absent even when outside the windows. It is the
   next window *start* strictly after now, searched up to 7 days ahead.
7. **Schedule changes while a session is open:** applied on the next scheduled-close tick (30 s). The rule
   evaluates "was the session opened inside a window" against the **new** schedule, so adding windows that
   cover the opening time but not "now" closes the session at the next tick; removing every window means the
   session is never closed automatically.
8. **Schedule replacement and `PATCH` (including `activo:false`) do not push an SSE snapshot**; neither do
   window boundaries. Screens must poll the status endpoint.
9. **SSE `id`/`colaVersion` is not monotonic across sessions** (closed = 0, a new session restarts at 0) and is
    unchanged by state-only pushes (pause, maintenance). `Last-Event-ID` is ignored.
10. **`Idempotency-Key` is allowed by CORS but never read** by any endpoint.
11. **CORS preflight rejects any extra header** (`x-request-id`, tracing headers) with `403 Invalid CORS
    request`.
12. **`SEARCH_BUDGET_EXHAUSTED` can answer a song request** (`POST /api/participante/solicitudes` of a song never
    stored needs a 1-unit lookup charged to the establishment budget), with a message that talks about
    "searches" (captured).
13. **`CATALOG_QUOTA_EXCEEDED` is `503`, not `429`,** while the per-establishment budget is `429`.
14. **The invalid `dispositivoToken` is silently replaced** with a new device (by design: no probing), so a
    client that keeps sending a wrong stored value gets a new pseudonym on every join unless it stores the
    returned one.
15. **`NO_ACTIVE_SESSION` is `409` on join but `404` on owner routes;** `NO_DEFAULT_LIST` is `404` on GET and
    `409` when opening a session.
16. **Playback works while paused:** `siguiente`/`fin`/`saltar`/`DELETE /cola` only require a session in
    operation (not `CERRADA`); they ignore the session pause and the system pause (captured for the session
    pause).
17. **`RESERVADA` and `CERRANDO` are part of the contract but never set on this commit.** `SESSION_ALREADY_CLOSED`,
    `SESSION_NOT_FOUND`, `SESSION_NOT_IN_OPERATION` and `ESTABLISHMENT_NOT_FOUND` on owner routes are
    practically unreachable through the API (races only).
18. **A vote may target the request that is currently playing** (`REPRODUCIENDO` counts as active); it only
    raises `votos`.
19. **Owner search has no per-user rate limit** and may consume the whole daily YouTube quota, starving
    participants (they then get `503 CATALOG_QUOTA_EXCEEDED`).
20. **All limits and the quota counters are in memory per instance** and reset on restart; behind a reverse proxy
    without `server.forward-headers-strategy`, every client shares the proxy address, so the per-address join
    and SSE limits become global.
21. **`PUT /lista-defecto` with an unconfigured/unavailable catalog accepts any well-formed id** (metadata is
    best effort) and stores songs with `null` title/duration/thumbnail; with a working catalog an unknown id is
    `400 VIDEO_UNAVAILABLE`.
22. **QR endpoint works for an inactive establishment** and does not depend on any session.
23. **`expiraAt` of a participant token is an upper bound only:** the token dies earlier when the session closes,
    when the device rejoins or when the participant leaves.

---

## 10. Appendix: server settings that change client-visible behavior

| Property (env) | Default | Effect |
|----------------|---------|--------|
| `app.public-base-url` (`PUBLIC_BASE_URL`) | `http://localhost:3000` | Base of the QR URL |
| `app.cors.allowed-origins` (`CORS_ALLOWED_ORIGINS`) | `http://localhost:3000` | Browser origins allowed |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` (`FUSIONAUTH_ISSUER`) | `http://127.0.0.1:9011` | Expected `iss` |
| `spring.security.oauth2.resourceserver.jwt.audiences` (`FUSIONAUTH_APPLICATION_ID`) | `c2f6a1b4-3d5e-4f70-8a9b-6e1d2c3b4a59` | Expected `aud` |
| `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` (`FUSIONAUTH_JWKS_URI`) | `http://localhost:9011/.well-known/jwks.json` | Signing keys |
| `app.youtube.api-key` (`YOUTUBE_API_KEY`) | empty | Empty = `503 CATALOG_NOT_CONFIGURED` |
| `app.youtube.daily-quota-limit` | 9000 | Units/day the server allows itself |
| `app.youtube.participant-quota-share` | 0.7 | Share of the daily limit for participants |
| `app.participante.search-units-per-establishment-per-day` | 2500 | Per-establishment participant budget |
| `app.youtube.search-cache-minutes` | 60 | Shared search cache |
| `app.youtube.max-results` | 15 | Results per search page (before filtering unplayable ones) |
| `app.participante.credential-ttl-hours` | 24 | `pt_` token upper bound |
| `app.participante.search-per-minute` | 6 | Participant searches/min |
| `app.participante.suggestions-per-minute` | 60 | Participant suggestions/min |
| `app.participante.queue-actions-per-minute` | 10 | Requests + votes/min |
| `app.participante.join-per-minute-per-address` / `-per-establishment` | 10 / 120 | Join limits |
| `app.participante.max-per-session` | 5000 | Participations per session |
| `app.privacy.policy-version` | 1.0 | Required `politicaVersion` |
| `app.cola.re-request-minutes` | 30 | Re-request window |
| `app.cola.max-active-per-session` | 200 | `QUEUE_FULL` threshold |
| `app.cola.sse-timeout-minutes` | 30 | Stream lifetime |
| `app.cola.sse-heartbeat-millis` | 15000 | Keep-alive interval |
| `app.cola.sse-max-per-establishment` / `-per-address` | 500 / 10 | Stream caps |
| `app.schedule.tick-seconds` / `min-open-seconds` | 30 / 60 | Scheduled close |
