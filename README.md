# Bridge AI — Trusted AI Negotiation Platform

A trusted, transparent environment for discussions, negotiations, and conflict resolution between
two or more people, each supported by a private AI assistant. No private information is disclosed
and no agreement is approved without explicit human confirmation.

- Product/spec: [docs/design-prompt.md](docs/design-prompt.md)
- Architecture: [docs/architecture.md](docs/architecture.md)
- Trust model (system invariants): [docs/trust-model.md](docs/trust-model.md)
- Security & threat model: [docs/security.md](docs/security.md)
- Phased backlog: [docs/backlog.md](docs/backlog.md)
- Public deployment guide (Render + Atlas + R2): [docs/deployment.md](docs/deployment.md)

Live deployment: https://bridge-ai-fuev.onrender.com

## Features

- **Discussions** with an explicit 11-state lifecycle (pause/close/reopen), roles
  (owner, party, advisor, observer), and plain-language Hebrew-first UI (English toggle, full RTL).
- **Invitations**: single-use hashed links with expiry and revocation; inviter provides the
  invitee's name (becomes their room display name); optional bilingual invitation email.
- **Join by code**: every room has a short join code (shown next to the room title, one-tap copy);
  a registered user enters it on the home page to request access, and a room admin approves
  (choosing the role: party / advisor / observer) or rejects. Admins can also promote other
  participants to admin (the creator can never be demoted).
- **Private AI assistant** per party: encrypted private notes, automatic calm rewording
  suggestions, private guidance (goals/boundaries/flexibility) feeding the negotiation.
- **Controlled sharing**: choose exactly who sees each message (parties / everyone / my advisors /
  selected people), exact-preview confirmation bound by content hash, immutable versions with
  frozen audience snapshots, provenance labels ("AI-assisted wording, approved by …"),
  withdraw-not-delete.
- **AI-to-AI negotiation**: the two assistants confer under one neutral protocol using only
  shared content, with schema-validated turns, stopping rules (missing info, sensitive
  disclosure, deadlock, possible agreement), turn/token/time budgets, private questions to their
  own user, and live progress over WebSocket.
- **Proposals & approvals**: versioned proposals; approval requests pinned to an exact version +
  hash; independent, idempotent, human-only decisions with equal-weight Approve/Reject/Request
  changes; any revision voids pending approvals; agreement only when every party approves the
  same version.
- **Outcome documents**: AI discussion summary, deterministic approved understandings (with
  per-party approval records), and an AI agreement draft ("not legal advice") — versioned,
  labeled, printable/exportable from the Result page.
- **Files**: images/documents up to 50MB following the same trust model, S3-compatible storage
  (local disk / AWS S3 / Cloudflare R2 / MinIO by env var), avatars with generated-initial fallback.
- **Transparency**: hash-chained append-only audit, authorization-filtered plain-language
  timeline, participants panel with live presence.
- **Platform**: JWT auth with rotating refresh tokens, AES-256-GCM field encryption for private
  content, rate limiting, transactional outbox, in-app notifications, i18n, OpenAPI docs at
  `/swagger-ui.html`.

## Repository layout

```
backend/    Kotlin + Spring Boot 3 modular monolith (JVM 17, MongoDB, Gradle)
frontend/   Angular 21 app (Hebrew/RTL default, standalone components, signals)
docs/       Architecture, trust model, security, backlog
```

## Prerequisites

- JDK 17 (`JAVA_HOME` must point to the JDK root, **not** its `bin` folder)
- Node.js 24+ and npm
- Docker (for MongoDB and Testcontainers-based integration tests)

## Setup

```bash
cp .env.example .env    # then edit values (at minimum JWT_SECRET)
```

Start MongoDB (single-node replica set, auto-initialized):

```bash
docker compose up -d mongodb
```

## Run for development

Backend (port 8080). Any launch without an explicit Spring profile — IDE run of
`DebateApplication`, or `bootRun` — starts in the `local` profile with a generated dev JWT secret:

```bash
./gradlew :backend:bootRun
```

MongoDB must be running first (see Setup). Deployments set `SPRING_PROFILES_ACTIVE` explicitly
(docker-compose sets `prod`), which makes a missing `JWT_SECRET` fail startup on purpose.

Frontend (port 4200, proxies `/api` to 8080):

```bash
cd frontend && npm ci && npm start
```

Open http://localhost:4200. API docs: http://localhost:8080/swagger-ui.html.

## Full stack in Docker

```bash
docker compose --profile app up --build
```

Frontend at http://localhost:8081, backend at http://localhost:8080.

## Public hosting

A single-container image (`Dockerfile.fullstack`) serves the UI, API, and WebSocket from one
origin; `render.yaml` is a ready Render blueprint. See [docs/deployment.md](docs/deployment.md)
for the full walkthrough (Render + MongoDB Atlas free tier + Cloudflare R2).

## Demo data

With the backend in the `local` profile and `SEED_DEMO=true` in the environment (or
`app.seed.enabled=true`), a demo discussion is seeded on startup (idempotent):

| Login | Role |
| --- | --- |
| `dana@demo.test` | Owner + party |
| `avi@demo.test` | Party |
| `yael@demo.test` | Advisor |
| `omer@demo.test` | Observer |

Password for all: `demo-password-123`. The room "חלוקת הוצאות הדירה" comes with agent profiles,
private notes, and two shared statements — ready for an assistants round.

## End-to-end test (Playwright)

`frontend/e2e/mvp-flow.spec.ts` walks the complete §17 flow with two browsers (register → invite →
private draft → exact-preview share → AI round → proposal → both approvals → all three outcome
documents). It is deterministic on the fake provider. To run:

```bash
docker compose up -d mongodb
```

Then start the backend with `LLM_PROVIDER=fake`, start the frontend (`npm start`), and:

```bash
cd frontend && npx playwright install chromium && npm run e2e
```

## Tests

```bash
./gradlew :backend:test        # unit + ArchUnit + Testcontainers integration tests
cd frontend && npm test        # Angular unit tests (vitest)
```

## Email invitations (optional)

By default invitations are shared as links and no email is sent. Two transports exist behind the
same `EmailSender` port, selected with `MAIL_PROVIDER`:

- **`smtp` (default, local development)** — Gmail: enable 2-Step Verification, create an App
  Password, set `MAIL_ENABLED=true`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `MAIL_FROM` in `.env`.
- **`brevo` (production)** — hosts like Render block outbound SMTP, so production sends over
  HTTPS via Brevo: set `MAIL_PROVIDER=brevo`, `BREVO_API_KEY`, `MAIL_FROM` (the sender address
  must be verified in Brevo). See [docs/deployment.md](docs/deployment.md).

Delivery is best-effort: a mail failure never blocks invitation creation — the shareable link
always remains available in the app, and the UI shows whether the email went out.

## Windows notes (encountered on this machine)

- If Gradle fails with `Unable to establish loopback connection`, the JVM temp path is the culprit;
  run with a short temp dir: `set TMP=C:\dev\tmp` (and `TEMP`) before `gradlew`.
- Docker Engine 29 rejects old Docker API versions. The backend test task pins
  `api.version=1.44` for Testcontainers automatically; override with the `DOCKER_API_VERSION`
  env var if your engine differs. If Testcontainers cannot find Docker at all, also set
  `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine`.

## CI

GitHub Actions ([.github/workflows/ci.yml](.github/workflows/ci.yml)) builds and tests the backend
(including integration tests) and lints/tests/builds the frontend on every push and pull request.
