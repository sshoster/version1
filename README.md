# Bridge AI — Trusted AI Negotiation Platform

A trusted, transparent environment for discussions, negotiations, and conflict resolution between
two or more people, each supported by a private AI assistant. No private information is disclosed
and no agreement is approved without explicit human confirmation.

- Product/spec: [docs/design-prompt.md](docs/design-prompt.md)
- Architecture: [docs/architecture.md](docs/architecture.md)
- Trust model (system invariants): [docs/trust-model.md](docs/trust-model.md)
- Security & threat model: [docs/security.md](docs/security.md)
- Phased backlog: [docs/backlog.md](docs/backlog.md)

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

## Tests

```bash
./gradlew :backend:test        # unit + ArchUnit + Testcontainers integration tests
cd frontend && npm test        # Angular unit tests (vitest)
```

## Email invitations (optional, Gmail SMTP)

By default invitations are shared as links and no email is sent. To also deliver invitations by
email through Gmail:

1. On the Google account that should send the emails, enable **2-Step Verification**.
2. Create an **App Password** (Google Account → Security → 2-Step Verification → App passwords).
3. In `.env`, set `MAIL_ENABLED=true`, `SMTP_USERNAME`, `SMTP_PASSWORD` (the app password), and
   `MAIL_FROM`; restart the backend with those variables in its environment.

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
