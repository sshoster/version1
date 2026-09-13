# Bridge AI — frontend

Angular 21 app (standalone components, signals, Hebrew-first RTL with an English toggle).
All project documentation lives at the repository root — see [../README.md](../README.md).

## Commands

```bash
npm start        # dev server on :4200, proxies /api and /ws to the backend on :8080
npm test         # unit tests (vitest)
npm run build    # production build to dist/frontend
npm run e2e      # Playwright E2E (see the root README: requires backend with the fake LLM provider)
```

## Structure

- `src/app/core/` — API services, auth (JWT + refresh interceptor), i18n dictionary, WebSocket
  (STOMP) room events, models.
- `src/app/features/` — pages: welcome/auth, home, creation wizard, the discussion room
  (shared/assistant tabs, negotiation, proposals, files, participants+presence, timeline),
  result documents, invite acceptance, info pages.
- `src/app/shared/` — reusable UI (avatar with generated-initial fallback).
- `e2e/` — the §17 end-to-end flow on two browser contexts.
