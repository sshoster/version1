# Phased Backlog — Trusted AI Negotiation Platform

Status: ALL PHASES COMPLETE — the MVP definition of done (§17) is met; 69 backend tests green. Each phase ends with green builds
and tests, and a short report (done / files / results / risks / next). Acceptance-test numbers refer to design
doc §14.

---

## Phase 1 — Foundation ✅

**Goal:** monorepo builds, auth works, rooms + invitations + RBAC exist, Mongo replica set + migrations run.

- [x] Restructure to monorepo: `backend/` (Kotlin, Spring Boot, JVM 17 toolchain), `frontend/` (Angular workspace); remove placeholder `Main.java`; root `settings.gradle.kts` includes `:backend`.
- [x] `docker-compose.yml` with single-node Mongo replica set (idempotent auto-init), `.env.example`, README bootstrap commands.
- [x] Mongock setup + first changelogs (users, rooms, participants, invitations indexes).
- [x] `shared` primitives: UUIDv7, error model, correlation-ID filter, structured logging + redaction, idempotency record support, domain-event/outbox skeleton.
- [x] identity: register/login/refresh with Argon2id + rotating refresh tokens; `GET /users/me`.
- [x] discussion: `DiscussionRoom` aggregate + full state machine via `RoomLifecycleService` (atomic conditional transitions, audit + outbox in txn).
- [x] participants: invitations (hashed single-use tokens, expiry, revoke, accept), roles, participant management endpoints.
- [x] permissions: `PermissionsService` v1 (role + membership checks), wired into all endpoints.
- [x] audit: append-only writer with hash chaining; no mutation API.
- [x] Testcontainers (Mongo replica set) integration-test harness; ArchUnit module-boundary rules.
- [x] CI pipeline: backend build/test, frontend lint/test/build.
- [x] Angular shell: auth screens, Home surface (discussion list + `Start a discussion`), i18n scaffolding (he default, RTL), design-system tokens.
- **Exit criteria (met):** register → create room → invite → accept → see room, all authorized server-side; negative authorization tests pass for rooms/invitations (part of tests 1, 16).

## Phase 2 — Privacy and sharing ✅

**Goal:** the full private-draft → AI draft → preview → publish flow with immutable versions and audit.

- [x] messaging: private conversations/messages (`PRIVATE_TO_AUTHOR_AND_AI`), reads keyed by (roomId, caller) only. (Decision: no separate `PrivateConversation` document in the MVP — one conversation per user per room.)
- [x] llm: `LlmProvider` SPI + `FakeLlmProvider` (deterministic); AI draft endpoint with versioned prompt template (`prompts/draft/v1.md`).
- [x] Share previews (content-hash bound, 15-min TTL, single-use) + publish with server-side scope/recipient revalidation, `SharedItem` + `SharedItemVersion` + `AudienceSnapshot` + audit + outbox in one txn; `ContentOrigin` labels with server-validated draft references.
- [x] Withdrawal (no hard delete) + supersession via item versions (`itemId` on publish → new version, prior versions marked superseded).
- [x] notifications: WebSocket (STOMP) with CONNECT-frame auth + authorized SUBSCRIBE, audience-scoped events on per-user queues, in-app `Notification` documents (idempotent per event+user) + REST endpoints. (Frontend notification UI deferred to Phase 4's timeline work.)
- [x] Field-encryption layer (`FieldCipher`, AES-256-GCM) for private message bodies; key required outside local/test.
- [x] Angular: Discussion surface (shared/my-assistant tabs, trust indicators, composer with `Write myself` / `Help me phrase it`), scope picker, exact-preview dialog with derived origin, origin labels, withdrawn/version rendering, live updates over STOMP.
- **Exit criteria (met):** acceptance tests 1–7, 15, 16 automated and green (`PrivacySharingFlowIT`, `WebSocketAuthIT`).

## Phase 3 — Automated AI discussion ✅

**Goal:** bounded AI-to-AI rounds with separated contexts, structured output, stopping rules, live progress.

- [x] negotiation: `AiAgentProfile` (encrypted private guidance: goals/boundaries/flexibility), `NegotiationRun`/`NegotiationTurn`, single-active-run constraint (unique partial index).
- [x] Orchestrator: per-party context builders (allow-list from server state — own profile + private notes + only that party's visible shared facts + shared transcript), versioned prompt template (`prompts/negotiation/v1.md`), schema-validated structured turn output, fact-reference validation (citing unshared content fails the run with SAFETY).
- [x] Stopping rules (§6.4) + configurable max turns (default 10) + token/time budgets enforced pre-call; pause/resume; private questions to own user (encrypted answers) with auto-resume when all are answered.
- [x] OpenAI adapter (chat completions, JSON response format, retries with backoff, simple circuit breaker; key via OPENAI_API_KEY, model via OPENAI_MODEL); Fake remains the default with deterministic SCENARIO markers for tests.
- [x] Round-result summary (§6.5: agreed/unresolved/proposals/assumptions/recommended + stop reason) + WebSocket progress events (`NEGOTIATION_STARTED/TURN_COMPLETED/WAITING_FOR_USER`, `QUESTION_CREATED` scoped to the asked user) + question notifications.
- [x] Angular: `Let the assistants look for a solution` panel with live progress, transcript with per-assistant proposals, inline question answering, plain-language round summary, pause/resume/retry; agent-profile editor in the assistant tab.
- **Exit criteria (met):** acceptance tests 8, 9, 10, 17, 18 green (`NegotiationFlowIT` — 55 backend tests total); provider failure leaves domain state clean and retryable.

## Phase 4 — Proposals and approvals ✅

**Goal:** versioned proposals, independent human approvals, invalidation on revision, readable timeline.

- [x] agreements: proposals, immutable `ProposalVersion` documents, revisions, approval requests pinned to version + content hash, idempotent human-only approvals (unique per request+user, idempotency keys, same-decision replay safe).
- [x] Approval invalidation on any revision (pending requests superseded); obsolete-version approval → controlled 409 (expectedVersion check + pinned request version); room → `AGREED` only when every required party approved the same version; rejection/changes reopen the discussion.
- [x] Human-readable timeline projection (plain language, authorization-filtered: private events owner-only, audience-scoped events audience-only, negotiation/proposal events parties-only) + `GET /rooms/{id}/timeline`; authorized `GET /rooms/{id}/audit` from Phase 1.
- [x] Angular: proposals panel with version history, "create formal proposal from the assistants' recommendation", `Your approval is needed` flow with fair-weight Approve/Reject/Request changes (+ optional comment), revision editor with explicit consequence note, timeline under `What happened in this discussion`.
- **Exit criteria (met):** acceptance tests 11, 12, 13, 14, 21, 22 green (`ApprovalFlowIT` — 60 backend tests total).

## Phase 5 — Outcomes ✅

**Goal:** the three labeled outcome artifacts, exportable.

- [x] `DISCUSSION_SUMMARY` — AI-generated from material EVERY party can see; labeled aiGenerated, never presented as approved.
- [x] `APPROVED_UNDERSTANDINGS` — deterministic (no AI) from all-party-approved proposal versions, with per-party approval records (name, timestamp, version, content hash).
- [x] `AGREEMENT_DRAFT` — AI-generated from approved understandings + all-parties-shared facts only; draftOnly + notLegalAdvice labels; readable by advisors.
- [x] Print/export: standalone print window per artifact (title, label, meta, content).
- [x] Angular Result surface (`/rooms/:id/result`): all three artifacts with generate/regenerate, labels as badges, version stamps; room lifecycle buttons (pause/resume/close/owner-reopen) + endpoints.
- **Exit criteria (met):** acceptance tests 19, 20 green (`OutcomesFlowIT` — 65 backend tests total); MVP end-to-end flow (§17) demoable with Fake provider.

## Phase 6 — Hardening ✅

**Goal:** production-shaped quality gates.

- [x] Permission-matrix test suite (`PermissionMatrixIT`: 6 roles × 22 endpoint actions, non-members always 404, anonymous always 401).
- [x] Security review pass: in-memory rate limiting (per-IP auth budget + per-user write budget, 429; `RateLimitIT`), CSP + security headers in nginx, CORS allow-list (Phase 1), log-redaction by design (content never logged), npm audit in CI, SBOM via Syft in CI. (OWASP dependency-check for the backend deferred — needs an NVD API key; Dependabot recommended on the repo.)
- [x] Prompt-injection regression fixtures (`ContextInjectionTest`: participant content is flattened data, cannot forge context sections; plus the Phase 3 fact-reference rejection tests).
- [x] Accessibility review: keyboard focus outlines, aria roles on tabs/dialogs/alerts, 48px tap targets, text+icon (not color-only) status signals, RTL logical properties, 320px-safe flex layouts; plain-language copy audit (no internal jargon in UI strings).
- [x] Observability: Micrometer + actuator health/readiness probes, correlation IDs, structured log pattern; mail health excluded from readiness by design.
- [x] Basic performance validation (`PerformanceSmokeIT`: timeline + shared-items bounded by limit params and < 3s at 400/300 documents); pagination limits added to message/shared/timeline reads.
- [x] Seed data (SEED_DEMO=true: four demo users + ready discussion) + demo scenario in README; Playwright E2E scaffold for the §17 flow (`frontend/e2e/mvp-flow.spec.ts`, run locally against the fake provider).
- **Exit criteria (met):** the design doc's acceptance scenarios run green in CI as backend integration tests (69 tests); final report delivered per design doc §19.

## Beyond the plan (owner-requested additions)

- [x] Rebrand to **Bridge AI** (UI, email, docs).
- [x] Email invitations with a bilingual styled message; `EmailSender` port with Gmail SMTP (local) and Brevo HTTPS (production — Render blocks SMTP).
- [x] Invitations carry the invitee's first/last name → room display name; self-acceptance rejected without consuming the link.
- [x] File attachments (≤50MB) with the message trust model; `FileStorage` port: local disk + S3-compatible (AWS S3 / Cloudflare R2 / MinIO); avatars with generated-initial fallback.
- [x] Participants side panel with live per-room presence (🟢/🟡/⚪); hamburger menu (About, privacy explainer, Contact, language toggle); three-column room layout with sticky side panels; colored, categorized timeline.
- [x] Public hosting package: single-container image (backend serves the SPA), `render.yaml` blueprint, deployment guide — live at https://bridge-ai-fuev.onrender.com (Render + MongoDB Atlas + Cloudflare R2).
- [x] Join by code: per-room join code (copy chip in the header), join requests with admin approval + role choice; grantable admin (OWNER) role — creator undemotable, no self-edit.
- [x] Per-room unread badges on the home page: member-wide notifications for room activity (actor excluded), live pings over `/user/queue/notifications`, cleared when room content renders; unread rooms sort first.
- [x] App shell redesign: sliding nav drawer beside the title, avatar profile dropdown, `/profile` page (photo + account display name via `PATCH /users/me`, change password via `POST /users/me/password` requiring the current password), compact files list.
- [x] Mobile participants as a floating side button (online-count badge) expanding into a collapsible list; password minimum relaxed to 5 characters (owner decision).
- [x] Onboarding visuals: looping SVG/CSS "how it works" animation (Dani & Maya + their assistants) and a real-screenshot carousel (auto-slide, stops on manual pick) on the welcome, invite, and create pages; screenshot regeneration scripts under `frontend/e2e/`.
- [x] Sign in with Google (GIS ID-token flow, server-side audience verification, accounts matched by verified email); refresh-token TTL raised to 30 days for persistent sessions.

## Candidate next steps

- "Hide my presence" privacy toggle; per-party advisor assignment; rename-me for invited display names.
- CSP headers from the backend-served SPA; httpOnly-cookie sessions; Dependabot; ClamAV hook.
- Redis-backed presence/rate-limit/outbox for horizontal scaling; custom email domain (DKIM); custom app domain.
- Playwright E2E in CI; questions-to-other-party flow in negotiation rounds.
