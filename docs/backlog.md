# Phased Backlog — Trusted AI Negotiation Platform

Status: Phase 2 complete (privacy & sharing built and tested); Phase 3 is next. Each phase ends with green builds
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

## Phase 3 — Automated AI discussion

**Goal:** bounded AI-to-AI rounds with separated contexts, structured output, stopping rules, live progress.

- [ ] negotiation: `AiAgentProfile`, boundaries/flexibility intake, `NegotiationRun`/`NegotiationTurn`, single-active-run constraint.
- [ ] Orchestrator: per-party context builders (allow-list from server state), versioned prompt templates, schema-validated structured turn output, policy validation (`sharedFactsUsed` resolution, no trusted model IDs).
- [ ] Stopping rules (all of §6.4) + configurable max turns (default 10) + token/cost/time budgets; pause/resume; questions to users + answer endpoint + run resumption.
- [ ] OpenAI adapter with timeouts/backoff/circuit breaker (behind profile; Fake remains default; key via OPENAI_API_KEY).
- [ ] Round-result summary (§6.5) + WebSocket progress events (`NEGOTIATION_*`, `QUESTION_CREATED`).
- [ ] Angular: `Let the assistants look for a solution` flow, live progress, `What happens next` area, question answering inline.
- **Exit criteria:** acceptance tests 8, 9, 10, 17, 18 green; provider failure leaves domain state clean and retryable.

## Phase 4 — Proposals and approvals

**Goal:** versioned proposals, independent human approvals, invalidation on revision, readable timeline.

- [ ] agreements: proposals, `ProposalVersion` (+ diffs), revisions, approval requests, idempotent human-only approvals bound to version + content hash.
- [ ] Approval invalidation on any revision; obsolete-version approval → controlled conflict; room → `AGREED` only on all-parties-same-version.
- [ ] Human-readable timeline projection (plain language, authorization-filtered) + `GET /rooms/{id}/timeline`, authorized `GET /rooms/{id}/audit`.
- [ ] Angular: proposal view with version diff, `Your approval is needed` flow with fair-weight Approve/Reject/Request changes, timeline (`What happened in this discussion`).
- **Exit criteria:** acceptance tests 11, 12, 13, 14, 21, 22 green.

## Phase 5 — Outcomes

**Goal:** the three labeled outcome artifacts, exportable.

- [ ] `DISCUSSION_SUMMARY` (AI-generated, labeled, never presented as approved).
- [ ] `APPROVED_UNDERSTANDINGS` (only all-party-approved terms; per-party approval records; re-versioning invalidates approvals).
- [ ] `AGREEMENT_DRAFT` (from approved understandings + shared info only; draft/no-legal-advice labels; shareable with advisor).
- [ ] Print/export-friendly output (PDF or print CSS).
- [ ] Angular Result surface: all three artifacts in one place.
- **Exit criteria:** acceptance tests 19, 20 green; MVP end-to-end flow (§17) demoable with Fake provider.

## Phase 6 — Hardening

**Goal:** production-shaped quality gates.

- [ ] Permission-matrix test suite (role × action × scope).
- [ ] Security review pass against `security.md` (rate limiting, CORS/CSP, redaction verification, dependency scan, SBOM).
- [ ] Prompt-injection regression fixtures.
- [ ] Accessibility review (WCAG 2.2 AA, keyboard-only, 320px), usability copy check (no jargon leaks).
- [ ] Observability: metrics, health/readiness, structured-log review.
- [ ] Basic performance validation (timeline + message pagination under load).
- [ ] Seed data + documented demo scenario; final README; Playwright E2E for the §17 flow.
- **Exit criteria:** all 22 acceptance tests green in CI; final report per design doc §19.
