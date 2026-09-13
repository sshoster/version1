# Security & Threat Model — Trusted AI Negotiation Platform

Status: Phase 0 (design). Companion to [`trust-model.md`](trust-model.md) (information-flow invariants) and
[`architecture.md`](architecture.md) (mechanisms). Controls listed here are implemented and tested in Phases 1–6;
the phase column shows where each lands.

---

## 1. Assets

1. Private drafts, private assistant conversations, boundaries/flexibility ranges (highest sensitivity).
2. Shared negotiation content, proposals, approvals, outcome artifacts.
3. Audit history (integrity-critical).
4. Credentials, JWTs, refresh tokens, invitation tokens.
5. LLM API keys and cost budgets.
6. PII: names, emails, timezone.

## 2. Threat model (STRIDE × main surfaces)

| # | Threat | Vector | Controls | Phase |
| --- | --- | --- | --- | --- |
| T1 | IDOR / cross-room access | Direct API call with another room's resource ID | Room-scoped queries (`roomId` in every query), single permissions service on every endpoint + repository read; negative tests per endpoint | 1–2 |
| T2 | Privilege escalation via client input | Client-supplied recipient lists, scopes, roles, preview payloads | Server resolves scopes/recipients/roles from own state at confirm time; preview bound by content hash; DTO allow-lists | 2 |
| T3 | Prompt injection | Other party's messages/attachments contain instructions to the assistant | Untrusted-data framing in prompts (content quoted as data), schema-constrained structured output, server-side policy validation of every turn, `SAFETY` stop rule | 3 |
| T4 | AI-driven disclosure | Model leaks private context into `publicMessage`/proposal | `sharedFactsUsed` must resolve to items the audience can see; `SENSITIVE_DISCLOSURE` stop; context minimization; nothing auto-published — human preview+confirm gate | 3 |
| T5 | Forged approval / repudiation | Replay, AI-created approval, approving stale version | Human-principal-only approval endpoint, idempotency keys, unique `{approvalRequestId, participantId}`, version+hash binding, audit chain | 4 |
| T6 | Audit tampering | Update/delete of audit events | Append-only collection, no mutation API, hash chaining, audit written in same txn as domain change | 1+ |
| T7 | Invitation token theft/guessing | Link leakage, brute force | Single-use, high-entropy tokens stored **hashed** (unique index), expiry (TTL for pending only), revocation, no room data before authenticated acceptance, rate limiting | 1 |
| T8 | Session/token attacks | Stolen JWT, refresh replay | Short-lived access tokens, rotating refresh tokens with reuse detection, Argon2id password hashing, generic auth errors, rate-limited login | 1 |
| T9 | WebSocket leakage | Subscribing to another room's topic; private data in event payloads | SUBSCRIBE interceptor authorizes room membership; event payloads carry IDs + minimal data, content fetched via authorized REST; per-user filtering for scoped events | 2–3 |
| T10 | XSS / CSRF / CORS | Malicious content in shared messages | Angular default escaping (no `bypassSecurityTrust*` on user content), CSP, Bearer-token auth (no cookie CSRF surface; if cookies are ever used, CSRF tokens), strict CORS allow-list | 2, 6 |
| T11 | NoSQL injection / operator smuggling | `$`-prefixed keys in JSON input | Typed DTOs + Bean Validation (no raw map → query paths), Spring Data parameter binding, reject `$`/`.` keys in free-form fields | 1+ |
| T12 | Malicious attachments | Malware, oversized files, content-type confusion | Type/size allow-list, randomized storage names, metadata/content-type verification, malware-scanning hook (interface; no-op locally), attachments never fed to LLM as instructions | 2 |
| T13 | DoS / cost abuse | Endpoint flooding; LLM cost blow-up | Rate limiting (per-IP on auth, per-user on writes and run starts), request size limits, per-room/per-run token+cost budgets, max one active run per room, circuit breaker on provider | 3, 6 |
| T14 | Sensitive data in logs/errors | Stack traces, prompt contents in logs | Structured logging with redaction filter (message bodies, tokens, keys never logged), correlation IDs instead of content, generic client errors with error IDs | 1+ |
| T15 | Secret leakage | Hardcoded keys, committed .env | Secrets only via env vars / secret manager; `.env.example` placeholders; `.gitignore` for `.env`; no secrets in frontend bundle | 1 |
| T16 | Dependency compromise | Vulnerable libraries | Dependency scanning in CI (OWASP dependency-check / `npm audit`), SBOM (CycloneDX) if straightforward | 6 |
| T17 | Concurrency corruption | Lost updates, double runs, double publish | Optimistic locking / conditional atomic updates with `modifiedCount` verification, idempotency records, single-active-run constraint, transactional outbox | 1+ |

## 3. Authentication & session model

- Local JWT mode (dev-isolated, OIDC-ready seam): `POST /auth/register|login|refresh`.
- Access token ~15 min; refresh token rotated on every use, stored server-side hashed, family revoked on reuse detection.
- Passwords: Argon2id via Spring Security's encoder.
- All auth state validated server-side per request; WebSocket handshake authenticates via the same JWT.

## 4. Authorization model

- Single `PermissionsService`: `can(actor, action, resource)` combining role assignment, room membership, visibility scope, and audience snapshot.
- Checked at: controller entry, WebSocket SUBSCRIBE, timeline/audit projections, and (defense in depth) repository read paths that return content bodies.
- Deny by default; every new endpoint requires an authorization test (happy + negative) before merge — enforced as a review checklist item and by the permission-matrix test suite in Phase 6.

## 5. Encryption

- **In transit**: TLS terminated at the reverse proxy in deployment; local compose runs HTTP on localhost only (documented).
- **At rest**: MongoDB volume encryption is a deployment concern; additionally, highly sensitive private fields (private message bodies, boundaries/flexibility ranges) go through an application-level field-encryption layer (AES-GCM, key from env/secret manager) behind a `FieldCipher` interface, upgradeable to MongoDB CSFLE later. Local dev may use a static dev key from `.env`; production requires a managed key. |

## 6. Data retention, export, deletion

- Users can export their data (room content they are authorized to see + their private space).
- Account deletion removes private space and PII where possible, but **published shared history and audit
  events are retained** (pseudonymized actor where feasible) because other parties' agreement records depend on
  them. This tension is deliberate and documented to users at deletion time.
- Pending invitation tokens expire via TTL; accepted/revoked invitation records are retained for audit.

## 7. LLM-specific controls

- Provider keys only via env vars; never logged; never sent to the frontend.
- Context minimization: only allow-listed fields enter prompts; private data of other users never enters a context.
- No hidden chain-of-thought stored; operational metadata (tokens, cost, latency, template version) only.
- Budgets enforced *before* each call; runs stop at budget with an explicit stop reason.
- `FakeLlmProvider` is the default profile, so no key is required for dev/tests.

## 8. Security testing plan

- Negative authorization tests for every endpoint and WebSocket subscription (acceptance tests 1, 6, 7, 15, 16).
- Preview/confirm drift test (test 4), no-hard-delete test (test 5), AI-cannot-approve test (test 11).
- Idempotency and optimistic-lock conflict tests (tests 14, 21, 22).
- Permission-matrix suite (role × action × scope) in Phase 6.
- Prompt-injection regression suite: adversarial fixtures through `FakeLlmProvider` scenarios validating server-side rejection (tests 8, 9).
