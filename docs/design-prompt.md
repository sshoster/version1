# Complete Claude Code Prompt — Trusted AI Platform for Negotiation and Conflict Resolution

Copy this entire document into Claude Code. Act as a senior product architect, security engineer, and full-stack developer. Build a working end-to-end MVP, not merely an architecture document or static mockup.

---

## 1. Your role and working method

You are the Lead Software Architect and Senior Full-Stack Engineer responsible for designing and implementing an application with:

- A Kotlin backend.
- An Angular frontend.
- MongoDB as the primary database.
- Real-time communication.
- A provider-independent LLM integration layer.
- Strict authorization, transparency, and auditability.
- A local development environment using Docker Compose.
- Automated tests and complete setup documentation.

Before writing substantial code:

1. Inspect the existing repository, build tools, conventions, and installed versions.
2. If the repository is empty, create a monorepo. If code already exists, preserve its structure and do not rewrite unrelated files.
3. Present a short, phased implementation plan.
4. State any assumptions that materially affect the design.
5. Create `docs/architecture.md`, `docs/trust-model.md`, and `docs/security.md`.
6. Then implement the application incrementally.

For every phase:

- Write production-quality, maintainable code.
- Run the relevant build, lint, and test commands.
- Fix failures before moving forward.
- Do not leave critical behavior as pseudocode or TODO-only stubs.
- Never hard-code secrets, passwords, API keys, or tokens.
- Ask a focused question only when a missing decision would materially change the architecture or product behavior. For reversible decisions, choose a sensible default and document it.

---

## 2. Product vision

Build a trusted and transparent environment for discussions, negotiations, and conflict resolution between two or more people.

Each primary party has a private AI assistant. The assistants may conduct an automated discussion with one another using only information their users have explicitly approved for sharing. Their goal is not to win, manipulate, pressure, or develop adversarial tactics. Their purpose is to:

- Understand each party's needs, interests, concerns, and boundaries.
- Clarify misunderstandings.
- Separate facts, assumptions, positions, needs, and emotions.
- Identify agreements and unresolved issues.
- Suggest alternatives and mutually acceptable compromises.
- Ask users questions when essential information is missing.
- Maintain a calm, reliable, and transparent process.
- Help the parties reach a solution they both accept voluntarily.

The platform should support financial, personal, family, friendship, relationship, and business disputes. The MVP must not claim to provide legal advice or that an in-app approval is automatically a legally binding contract.

Core product statement:

> Two AI assistants help people conduct a transparent and documented agreement process, but no private information is disclosed and no final agreement is accepted without explicit human approval.

This is not a debate arena. The personal assistants are not adversarial lawyers. They protect their users' boundaries while following the same neutral protocol of fairness, transparency, non-deception, and constructive problem-solving.

---

## 3. Non-negotiable trust principles

These are mandatory system invariants, not optional UX recommendations:

1. **Private by default** — Everything a user writes to their private assistant remains private unless they explicitly share it.
2. **Explicit sharing** — Content leaves the private space only after the user selects a sharing scope and explicitly confirms the action.
3. **Exact preview** — Before sharing, show the exact final text, content type, and recipients.
4. **No silent AI actions** — AI may not disclose new information, change permissions, or approve an agreement for a user.
5. **Human final approval** — A final agreement becomes approved in the system only after every required party independently approves the exact same version.
6. **Clear provenance** — Every item identifies whether it was created by a user, AI, advisor, or the system.
7. **AI assistance label** — AI-generated or AI-edited content must display: `AI-assisted wording, approved by {displayName}`.
8. **Immutable shared history** — Once shared content has been published, it cannot disappear as though it never existed. It may be withdrawn or superseded, but the event and prior version remain in the authorized audit history.
9. **Version transparency** — Users can see what changed between proposal versions, who changed it, and which version each person approved.
10. **No hidden basis for agreement** — Private data may help a personal assistant advise its user, but it cannot be represented as a shared fact or used as a visible justification unless the user explicitly shares it.
11. **Server-side authorization** — Every read and write is authorized on the backend. Hiding an Angular component is never considered a security control.
12. **Neutral protocol** — Both assistants follow the same rules against deception, coercion, pressure, and manipulation.
13. **Explainable process** — Every critical action must be explainable later through a human-readable timeline and a technical audit trail.

Do not use blockchain. Implement an append-only audit log, optionally using hash chaining for tamper evidence.

---

## 4. Discussion model

Every negotiation is a persistent `DiscussionRoom` that may continue for days, weeks, or months.

### 4.1 Participant roles

- `OWNER` — Creates the room and controls administrative settings.
- `PARTY` — A primary party who may propose, answer, reject, request changes, and approve.
- `ADVISOR` — A lawyer or other advisor who may view authorized content and comment according to assigned permissions.
- `OBSERVER` — Read-only access to specifically authorized content.
- `FACILITATOR` — Optional/future role for a human mediator.

The MVP requires at least two `PARTY` participants in every active room.

### 4.2 Invitations

- Invite participants using email and/or a single-use invitation link.
- Every invitation has a role, expiration time, status, and securely stored token hash.
- Do not expose room data until the invitation is accepted by an authenticated user.
- The owner may revoke an invitation that has not yet been accepted.
- Role and permission changes must create audit events.

### 4.3 Room state machine

Implement an explicit state machine:

- `DRAFT`
- `INVITING`
- `INTAKE`
- `ACTIVE`
- `WAITING_FOR_USER`
- `PROPOSAL_READY`
- `AGREEMENT_PENDING_APPROVAL`
- `AGREED`
- `PAUSED`
- `CLOSED`
- `ARCHIVED`

State transitions must go through a domain service, validate the current state and actor permissions, and emit a domain event.

---

## 5. Private information and controlled sharing

### 5.1 Visibility scopes

Define an explicit `VisibilityScope`:

- `PRIVATE_TO_AUTHOR_AND_AI`
- `MY_ADVISORS`
- `SELECTED_PARTICIPANTS`
- `ALL_PARTIES`
- `ALL_ROOM_PARTICIPANTS`

For each published item, store an immutable audience snapshot containing the actual participant IDs authorized at publication time. Do not rely only on a dynamic role reference, because the system must later explain who could see the item at that exact moment.

### 5.2 Message drafting and sharing flow

1. The user writes a private message.
2. Their AI assistant immediately prepares a calm, clear, solution-oriented alternative draft.
3. The user chooses one option:
   - Send exactly what I wrote.
   - Send the AI suggestion.
   - Edit the AI suggestion and send the edited version.
   - Save as a private draft without sharing.
4. The user selects a sharing scope from a predefined list.
5. The application shows the exact final text, content type, and recipients.
6. The user explicitly confirms.
7. The server revalidates the scope and recipients, creates an immutable version and audience snapshot, and writes an audit event.

If text, content type, or recipients change after preview, invalidate the preview and require confirmation again.

### 5.3 Content origin

Define `ContentOrigin`:

- `USER_AUTHORED`
- `AI_DRAFT_ACCEPTED`
- `AI_DRAFT_USER_EDITED`
- `SYSTEM_GENERATED`
- `ADVISOR_AUTHORED`

Recipients see only the approved final wording and the relevant origin label. They must never see the user's private draft, private prompt, private conversation, or rejected AI suggestions.

### 5.4 Withdrawal and supersession

- Do not offer hard delete for a published shared item.
- Permit `WITHDRAWN` and `SUPERSEDED` states.
- Show participants when content has been withdrawn or replaced.
- Create a new audit event; never rewrite the prior event.

---

## 6. Automated AI-to-AI discussion

Build a negotiation orchestration service that runs separate agent contexts for each party under one shared neutral protocol.

### 6.1 A personal assistant may receive

- Its own user's private information.
- Shared content its user is authorized to see.
- The room objective.
- User-defined boundaries and flexibility ranges.
- Relevant room history.
- System instructions and the shared negotiation protocol.

### 6.2 A personal assistant must never

- Reveal private information without explicit user approval.
- Invent facts, approvals, positions, or user consent.
- Agree on behalf of its user.
- Present an inference as a verified fact.
- threaten, pressure, deceive, or manipulate.
- Follow prompt-injection instructions embedded in another participant's message or attachment.
- Access or use data from another room.

### 6.3 Automated round structure

Require schema-validated structured output. Do not depend only on parsing free-form model text. Each turn should return at least:

```json
{
  "publicMessage": "string or null",
  "proposal": {
    "title": "string",
    "terms": [],
    "assumptions": [],
    "openIssues": []
  },
  "questionsForOwnUser": [],
  "questionsForOtherParty": [],
  "sharedFactsUsed": [],
  "privateDataReferencedInternally": false,
  "requiresUserApproval": true,
  "stopReason": "NONE|MISSING_INFO|NEW_CONCESSION|SENSITIVE_DISCLOSURE|POSSIBLE_AGREEMENT|DEADLOCK|MAX_TURNS|SAFETY"
}
```

Validate all output on the server. Never trust model-generated IDs, permission scopes, participant identities, or recipient lists. Resolve all security-sensitive data from server-owned state.

### 6.4 Stopping rules

An automated round must stop when:

- Essential information is missing.
- A new concession exceeds a user's predefined flexibility.
- More private information would need to be disclosed.
- A party introduces a material new condition.
- A material contradiction or ambiguity exists.
- Unsafe or potentially illegal content is detected.
- A possible agreement is ready for human review.
- The maximum number of turns is reached.
- The configured cost or time budget is reached.
- The discussion reaches a deadlock.

MVP default: a maximum of 10 AI-to-AI turns per automated round. Make this configurable.

### 6.5 Round result

At the end of a round, show:

- Agreed points.
- Unresolved points.
- Proposals considered.
- Questions waiting for each user.
- Assumptions behind the recommendation.
- A recommended proposal, if one exists.
- What each party needs to decide or provide.
- A short explanation of why the round stopped.

### 6.6 LLM provider abstraction

Keep domain and orchestration logic independent of any vendor SDK:

```kotlin
interface LlmProvider {
    suspend fun generate(request: LlmRequest): LlmResponse
}
```

Support adapters for OpenAI and Anthropic over time. The MVP may implement one real provider, but it must also include a deterministic `FakeLlmProvider` for local development, integration tests, and E2E tests.

Provider rules:

- API keys only through environment variables or a secret manager.
- Configurable model name.
- Timeouts, exponential backoff, and a circuit breaker.
- Token and cost budgets per room and per run.
- Versioned prompt templates.
- Store operational metadata and cost usage, but never store hidden chain-of-thought.
- Minimize private information sent to the provider.
- Use correlation IDs without writing sensitive content into ordinary logs.

---

## 7. Three outcome levels

Implement three distinct outcome artifacts.

### 7.1 Discussion summary — `DISCUSSION_SUMMARY`

- Generated by AI.
- Includes subjects, positions, questions, agreements, and unresolved issues.
- Must not be presented as an approved agreement.
- Label clearly: `AI-generated discussion summary`.

### 7.2 Approved understandings — `APPROVED_UNDERSTANDINGS`

- Contains only terms explicitly approved by every required party.
- Store a separate approval for each party, including timestamp, artifact version, and content hash.
- Any content change creates a new version and invalidates approvals for the previous version.
- Provide a printable/exportable summary.

### 7.3 Agreement draft — `AGREEMENT_DRAFT`

- Generated only from approved understandings and shared information.
- Clearly labeled as a draft for review, not legal advice and not a guarantee of legal enforceability.
- Can be shared with a lawyer or advisor.
- Qualified electronic signatures are outside the MVP. The MVP provides authenticated in-app approval and document export.

---

## 8. Backend architecture

Use Kotlin on JVM 21 with a stable, compatible Spring Boot version and Gradle Kotlin DSL. Do not use experimental versions. If the repository already constrains versions, prefer compatibility and document the selected versions.

### 8.1 Recommended technologies

- Kotlin with coroutines.
- Spring Boot.
- Spring MVC or Spring WebFlux: choose one consistent approach and explain the decision.
- Spring Security.
- OAuth2/OIDC-ready authentication. A clearly isolated local JWT authentication mode is acceptable for development.
- MongoDB as a replica set in development, tests, and production wherever transactions are required.
- Spring Data MongoDB. Match reactive or non-reactive data access to the selected web stack.
- Mongock or an explicit, idempotent versioned migration mechanism for document transformations, index creation, and backfills. Do not rely only on runtime auto-index creation in production.
- WebSocket for real-time communication. SSE may be used for a strictly one-way stream when it simplifies a specific component.
- Bean Validation.
- OpenAPI 3.
- Testcontainers with a MongoDB replica set.
- JUnit 5, MockK, and ArchUnit or Konsist where useful.
- Micrometer metrics and structured logging with sensitive-data redaction.

### 8.2 Modular monolith

Start as a modular monolith, not microservices. Suggested bounded modules/packages:

- `identity`
- `discussion`
- `participants`
- `permissions`
- `messaging`
- `negotiation`
- `agreements`
- `audit`
- `notifications`
- `llm`
- `shared`

Maintain clear domain, application, infrastructure, and API boundaries. Do not create unnecessary physical modules if package boundaries are sufficient for the MVP.

### 8.3 MongoDB collections and documents

Design explicit documents and collections for:

- `User`
- `DiscussionRoom`
- `Participant`
- `Invitation`
- `RoleAssignment`
- `PrivateConversation`
- `PrivateMessage`
- `SharedItem`
- `SharedItemVersion`
- `AudienceSnapshot`
- `Proposal`
- `ProposalVersion`
- `ProposalTerm`
- `Question`
- `Answer`
- `AiAgentProfile`
- `NegotiationRun`
- `NegotiationTurn`
- `ApprovalRequest`
- `Approval`
- `OutcomeArtifact`
- `AttachmentMetadata`
- `AuditEvent`
- `Notification`
- `OutboxEvent`
- `IdempotencyRecord`

Use UUID or ULID consistently. Store timestamps in UTC and display them in the user's timezone.

Use embedding only for small, bounded data with the same lifecycle as its aggregate. Never embed arrays that may grow without a strict bound inside `DiscussionRoom`. Messages, item versions, negotiation turns, audit events, notifications, and outbox events must use separate collections referencing `roomId` and stable resource IDs.

Include a `schemaVersion` in documents that require evolution. Store historical snapshots as immutable documents instead of destructively updating previous versions.

### 8.4 MongoDB indexes and data access

Create and document explicit indexes for at least:

- Rooms by participant/user, status, and `updatedAt`.
- `roomId + createdAt` for messages, shared items, negotiation turns, audit events, and timeline queries.
- A unique hashed invitation token.
- TTL indexes for temporary invitation or authentication tokens, without deleting records required for audit history.
- A unique idempotency index on `roomId + operationType + idempotencyKey`.
- Unique compound indexes on `proposalId + version` and `outcomeArtifactId + version`.
- A unique approval index on `approvalRequestId + participantId`.
- Outbox processing indexes on `status + nextAttemptAt`.

Every repository query that returns room information must include or verify the `roomId` and the current user's authorization. Do not fetch a document by `_id` alone and assume authorization was checked elsewhere. Use projections so private fields are not retrieved when they are not required.

### 8.5 Audit log

`AuditEvent` is append-only and contains at least:

- Event ID.
- Room ID.
- Actor type and actor ID.
- Action type.
- Target type and target ID.
- Timestamp.
- Visibility/audience snapshot reference.
- Request/correlation ID.
- Minimal, non-sensitive metadata.
- Previous hash and event hash when hash chaining is enabled.

Do not expose a normal API that updates or deletes audit events.

### 8.6 Concurrency and idempotency

- Use `@Version` optimistic locking or atomic compare-and-set operations for mutable aggregates, proposals, and artifacts.
- Approval endpoints must be idempotent.
- Prevent approval of an obsolete version.
- Prevent two negotiation runs from running concurrently in the same room unless explicitly designed otherwise.
- Use idempotency keys for invitation creation, publication, run startup, and approvals.
- For changes affecting multiple documents, write the domain change, audit event, and outbox event inside one MongoDB multi-document transaction.
- Require a replica set in Docker Compose and Testcontainers wherever transactions are used.
- Avoid unprotected read-modify-write sequences. Prefer atomic conditional updates using status/version predicates and verify `modifiedCount`.

### 8.7 Transactional outbox

Use a dedicated MongoDB outbox collection for notifications and WebSocket events. Write the domain state change, `AuditEvent`, and `OutboxEvent` in the same transaction. A retry-safe worker must atomically claim pending events, publish them, and mark them processed. Include retry count, `nextAttemptAt`, a dead-letter state, and idempotent consumers.

---

## 9. API design

Build a versioned REST API under `/api/v1` plus authorized WebSocket events. Exact naming may follow repository conventions, but the following capabilities are required.

### Authentication

- `POST /auth/register`
- `POST /auth/login`
- `POST /auth/refresh`
- `GET /users/me`

### Rooms and invitations

- `POST /rooms`
- `GET /rooms`
- `GET /rooms/{roomId}`
- `PATCH /rooms/{roomId}`
- `POST /rooms/{roomId}/invitations`
- `GET /invitations/{token}`
- `POST /invitations/{token}/accept`
- `POST /rooms/{roomId}/invitations/{invitationId}/revoke`
- `GET /rooms/{roomId}/participants`
- `PATCH /rooms/{roomId}/participants/{participantId}`

### Private assistant

- `POST /rooms/{roomId}/private-messages`
- `GET /rooms/{roomId}/private-messages`
- `POST /rooms/{roomId}/private-messages/{messageId}/ai-draft`

### Sharing

- `POST /rooms/{roomId}/share-previews`
- `POST /rooms/{roomId}/shared-items`
- `GET /rooms/{roomId}/shared-items`
- `GET /rooms/{roomId}/shared-items/{itemId}/versions`
- `POST /rooms/{roomId}/shared-items/{itemId}/withdraw`

The server must recalculate authorization and recipients when publication is confirmed. Never trust an old preview or recipient IDs provided by the client.

### Negotiation

- `POST /rooms/{roomId}/negotiation-runs`
- `GET /rooms/{roomId}/negotiation-runs/{runId}`
- `POST /rooms/{roomId}/negotiation-runs/{runId}/pause`
- `POST /rooms/{roomId}/negotiation-runs/{runId}/resume`
- `GET /rooms/{roomId}/questions`
- `POST /rooms/{roomId}/questions/{questionId}/answer`

### Proposals and approvals

- `GET /rooms/{roomId}/proposals`
- `GET /rooms/{roomId}/proposals/{proposalId}`
- `POST /rooms/{roomId}/proposals/{proposalId}/revisions`
- `POST /rooms/{roomId}/proposals/{proposalId}/request-approval`
- `POST /rooms/{roomId}/approval-requests/{approvalRequestId}/approve`
- `POST /rooms/{roomId}/approval-requests/{approvalRequestId}/reject`

### Outcomes and history

- `POST /rooms/{roomId}/outcomes/summary`
- `POST /rooms/{roomId}/outcomes/approved-understandings`
- `POST /rooms/{roomId}/outcomes/agreement-draft`
- `GET /rooms/{roomId}/outcomes`
- `GET /rooms/{roomId}/timeline`
- `GET /rooms/{roomId}/audit` for authorized users only; never expose sensitive internal metadata.

Generate OpenAPI documentation with examples for security-critical requests and responses.

---

## 10. Real-time events

Clients subscribe to room events only after server-side authorization:

- `ROOM_UPDATED`
- `PARTICIPANT_JOINED`
- `SHARED_ITEM_PUBLISHED`
- `SHARED_ITEM_WITHDRAWN`
- `NEGOTIATION_STARTED`
- `NEGOTIATION_TURN_COMPLETED`
- `NEGOTIATION_WAITING_FOR_USER`
- `QUESTION_CREATED`
- `PROPOSAL_CREATED`
- `PROPOSAL_REVISED`
- `APPROVAL_REQUESTED`
- `APPROVAL_RECORDED`
- `OUTCOME_CREATED`

Every event includes `eventId`, `roomId`, `type`, `occurredAt`, `resourceId`, and `resourceVersion`. Never include private content in an event payload unless the subscribed user is authorized to receive it.

---

## 11. Angular frontend

Use a stable Angular version compatible with the installed Node environment. Prefer:

- Standalone components.
- Strict TypeScript.
- Signals for local UI state.
- RxJS for HTTP and WebSocket streams.
- Angular Router and route guards.
- Reactive Forms.
- A consistent SCSS-based design system.
- Full RTL support and Hebrew as the initial default language, with an i18n structure ready for English.
- Mobile-first responsive design.
- WCAG 2.2 AA where practical.
- Unit tests and E2E tests using a repository-compatible tool such as Playwright.

Do not use `any` without a documented reason. Generate the API client from OpenAPI or implement one strongly typed central client.

### 11.1 Radical simplicity and plain-language UX

The application must be usable without training or technical knowledge. A first-time user should always understand what is happening, what is private, what the other party can see, and what single action is expected next.

Apply these mandatory rules:

- Use ordinary human language in all user-facing text. Never expose internal terms such as `LLM`, `agent`, `prompt`, `token`, `scope`, `artifact`, `audit`, `state machine`, `WebSocket`, `version hash`, or enum values.
- Prefer one clear question and one primary action at a time.
- Keep advanced details behind `More details` and never require users to understand them to complete the normal flow.
- Use progressive disclosure: show only what is needed for the current step.
- Do not create a dashboard full of cards, statistics, menus, or status widgets.
- Do not require users to navigate between many pages to finish one task.
- Preserve context after every action; never make the user remember information from a previous screen.
- Use short sentences, familiar verbs, large tap targets, and reassuring confirmation text.
- Avoid legal or business language unless the user opens a detailed explanation.
- Whenever a decision is important, explain its consequence in one sentence before confirmation.
- Show at most one high-emphasis primary button in a section or dialog.
- Never use color alone to communicate privacy, approval, warning, or status.
- Every empty state must tell the user what to do next.
- Errors must explain what happened and provide a direct recovery action; do not show technical codes.
- Use sensible defaults and remember user choices, while requiring fresh approval for every disclosure or agreement.

Use friendly UI language such as:

| Internal term | User-facing wording |
| --- | --- |
| DiscussionRoom | Discussion |
| PARTY | Participant in the discussion |
| ADVISOR | Advisor |
| OBSERVER | Viewer |
| Private assistant | My assistant |
| Visibility scope | Who can see this? |
| Shared item | Message shared in the discussion |
| Negotiation run | Let the assistants look for a solution |
| Proposal version | Updated proposal |
| Approval request | Your approval is needed |
| Audit/timeline | What happened in this discussion |
| Withdraw item | Mark this message as withdrawn |
| Agreement artifact | Summary or agreement draft |

The AI assistant should guide the user conversationally instead of presenting a long form. For example, creating a discussion should ask three short questions one at a time:

1. `Who would you like to talk with?`
2. `What would you like to resolve?`
3. `Is there anything important the other person should know now?`

Allow `Skip for now` for nonessential questions and show progress using simple language such as `Step 2 of 3`.

### 11.2 Minimal screen model

The regular user experience should have only three main surfaces plus lightweight overlays:

1. **Home** — Current discussions, invitations, and a single `Start a discussion` action. Sort by `Needs your attention` and `Waiting for the other person`; do not build a complex dashboard.
2. **Discussion** — One continuous, chat-like workspace containing the shared conversation, private assistant interaction, questions, proposals, and current next action. Use inline sections or a simple two-position switch such as `Shared discussion` / `My assistant`, without routing to separate pages.
3. **Result** — The discussion summary, approved understandings, and agreement draft in one place.

Use dialogs, bottom sheets, or inline expansion for:

- Inviting a person.
- Selecting who can see a message.
- Reviewing the exact text before sharing.
- Approving, rejecting, or requesting a change.
- Viewing older versions and advanced history.
- Managing viewers and advisors.

Settings, full history, participant permissions, and technical/legal details must remain secondary and available through `More`, not permanent top-level navigation.

Do not create separate full pages for AI progress, open questions, proposal lists, approvals, participants, or history unless usability testing proves they are necessary. Surface the current relevant item directly inside the discussion.

### 11.3 One-screen discussion experience

The Discussion surface must include, without visual overload:

- A short title and one plain-language status sentence, such as `Dana is reviewing your proposal`.
- A compact trust indicator: `Private — only you and your assistant` or `Shared with Dana and her advisor`.
- The conversation or current proposal.
- One prominent `What happens next` area.
- A composer with two obvious choices: `Write myself` and `Help me phrase it`.
- A persistent but quiet way to switch between `Shared discussion` and `My assistant`.
- Older events collapsed under `Earlier in this discussion`.

Do not use six permanent tabs. Questions, proposals, and approval requests appear in chronological context and remain accessible through the timeline.

Clearly distinguish:

- Private content.
- Shared content.
- User-authored wording.
- `AI-assisted wording, approved by {name}`.
- An unapproved AI suggestion.
- Awaiting approval.
- Approved by only one party.
- Approved by every required party.
- Withdrawn or superseded content.

Do not rely on color alone. Use text, icons, and accessible labels.

### 11.4 Critical sharing UX

The composer must show:

- The user's original text.
- A ready AI alternative.
- The ability to edit the AI alternative.
- A clear choice of original, AI suggestion, or edited version.
- Sharing-scope selection.
- Exact final preview.
- Deliberate confirmation.

Changing content or recipients after preview requires a new preview and confirmation.

### 11.5 Trust-oriented UX

- Every shared item displays author, origin, timestamp, and sharing scope or recipients when authorized.
- Every approval identifies the exact proposal version.
- Present a human-readable timeline rather than a technical event log.
- Clearly identify all AI-authored messages.
- Do not use dark patterns to encourage approval.
- `Approve`, `Reject`, and `Request changes` must have fair visual weight.

### 11.6 Simplicity acceptance criteria

- A new user can start a discussion, invite another person, and send an approved shared message without documentation.
- A user can determine within three seconds whether the current text is private or shared and who can see it.
- Starting a new discussion requires no more than three required questions.
- Sharing a message requires no more than two confirmation steps after the text is ready: choose recipients, then confirm exact preview.
- The main navigation contains no more than three primary destinations.
- At any moment there is at most one primary action competing for attention.
- The common end-to-end flow does not require opening settings, history, permissions, or technical details.
- Test core flows at 320px mobile width and with keyboard-only navigation.
- Run usability checks using participants unfamiliar with AI terminology. If they ask what a label means, replace the label with simpler wording rather than adding a tooltip.

---

## 12. Security and privacy

Create a basic threat model in `docs/security.md` and implement protections for:

- Authorization on every endpoint and every WebSocket subscription.
- IDOR prevention through room membership and resource-level authorization.
- Encryption in transit.
- Encryption at rest. Highly sensitive private fields should support MongoDB Client-Side Field Level Encryption or a documented application-level encryption layer.
- Data-model separation between private and shared content.
- Sensitive-data redaction in logs and errors.
- Rate limiting.
- CSRF, XSS, and CORS controls appropriate to the authentication model.
- Refresh-token rotation when refresh tokens are used.
- Strong password hashing if credentials are stored locally.
- Attachment type/size restrictions, randomized names, and a malware-scanning hook.
- Prompt-injection defenses: participant messages and attachments are untrusted data, never system instructions.
- Cross-room data-leak prevention.
- Data retention, export, and deletion policies while preserving required audit evidence; document the tension between user deletion and immutable agreement records.
- Secret management through environment variables or a secret manager.
- Dependency scanning and an SBOM if straightforward to add.

Write negative authorization tests, not only happy-path tests.

---

## 13. Notifications

Implement in-app notifications for the MVP and define an abstraction for future email and push notifications.

Notify users when:

- They are invited to a discussion.
- New information they are authorized to see is published.
- Their AI assistant requires an answer.
- A proposal is created.
- Their approval is required.
- Another party approves, rejects, or requests a change.
- The discussion is paused or resumed.

Do not expose sensitive content in notification previews unless the user has explicitly enabled it.

---

## 14. Required acceptance tests

Automate at least these scenarios:

1. Party A's private message is inaccessible to Party B, observers, and unauthorized advisors.
2. Party A receives an AI draft, edits it, selects recipients, sees an exact preview, and confirms publication.
3. Party B sees the final text and `AI-assisted wording, approved by ...`, but not the original private draft.
4. Changing content after preview requires a new preview and confirmation.
5. A shared item cannot be hard-deleted; withdrawal creates a new event.
6. An observer cannot send, edit, approve, or change room state.
7. An advisor sees only scopes assigned to them.
8. The shared AI-to-AI discussion uses only content approved for sharing.
9. A run stops with `SENSITIVE_DISCLOSURE` or `MISSING_INFO` when new private data is required.
10. A run stops and summarizes after 10 turns.
11. AI cannot create an `Approval` for a human user.
12. One party's approval does not mark an agreement as fully approved.
13. A proposal revision invalidates prior approvals.
14. Repeating an approval request with the same idempotency key does not create duplicate actions.
15. WebSocket does not deliver private events to unauthorized users.
16. A user in one room cannot access a valid resource ID belonging to another room.
17. LLM provider failure does not corrupt domain state and can be safely retried.
18. `FakeLlmProvider` supports deterministic E2E tests without an external API key.
19. All three outcome types can be generated with the correct labels.
20. A discussion can remain available, be paused, closed, reopened when authorized, and reviewed later.
21. MongoDB optimistic-lock conflicts return a controlled conflict response rather than silently overwriting data.
22. Domain state, audit event, and outbox event commit or roll back together.

---

## 15. Local development and operations

Create:

- `docker-compose.yml` containing a MongoDB replica set, backend, and frontend as appropriate. Initialize the replica set automatically and idempotently.
- `.env.example` with no real secrets.
- Mongock or versioned MongoDB migrations, including index creation and backfills.
- Optional seed data with two demo parties, one observer, one advisor, and a demo room.
- Health and readiness endpoints that verify required dependencies.
- A README with exact commands for setup, development, testing, and production builds.
- A Makefile or simple scripts if they materially improve local development.
- CI that runs backend tests, frontend tests, linting, and builds.

Add a local mode where `FakeLlmProvider` is the default so the complete flow works without an external API key.

---

## 16. Required implementation order

### Phase 0 — Design

- Inspect the repository.
- Document architectural decisions.
- Add compact Mermaid diagrams for domain boundaries and critical flows.
- Create the trust model and threat model.
- Produce a phased backlog.

### Phase 1 — Foundation

- Monorepo, builds, Docker Compose, and CI.
- Basic authentication.
- MongoDB replica set, collections, indexes, and migrations.
- Users, rooms, participants, invitations, and RBAC.

### Phase 2 — Privacy and sharing

- Private assistant conversation.
- AI draft using `FakeLlmProvider`.
- Share previews, visibility scopes, and audience snapshots.
- Shared item versions, withdrawal, and audit history.
- Complete Angular sharing flow.

### Phase 3 — Automated AI discussion

- LLM abstraction.
- Separate agent contexts.
- Negotiation orchestration and state machine.
- Structured-output validation.
- Stopping rules, budgets, and WebSocket progress.
- Questions to users and run resumption.

### Phase 4 — Proposals and approvals

- Proposals and version diffs.
- Approval requests and independent approvals.
- Approval invalidation after any revision.
- Human-readable timeline.

### Phase 5 — Outcomes

- Discussion summary.
- Approved understandings.
- Agreement draft.
- Print/export-friendly output.

### Phase 6 — Hardening

- Permission-matrix tests.
- Security review.
- Accessibility review.
- Observability.
- Basic performance validation.
- README and documented demo flow.

At the end of each phase, report:

- What was completed.
- Important files created or changed.
- Build and test results.
- Open risks or decisions.
- The next phase.

---

## 17. MVP definition of done

The MVP is complete only when this end-to-end flow works:

1. User A registers and creates a room.
2. User A invites User B, an observer, and optionally an advisor.
3. User B accepts the invitation.
4. Each party has a private conversation with their own AI assistant.
5. Each user receives a ready AI wording suggestion.
6. The user chooses original/AI/edited wording, selects a sharing scope, reviews the exact preview, and confirms.
7. The two assistants conduct a bounded automated discussion.
8. The round stops for a user question or produces a possible proposal.
9. Both parties review the exact same proposal version and approve independently.
10. The system generates a discussion summary, approved understandings, and an agreement draft.
11. Every relevant event appears in a transparent timeline.
12. Unauthorized users cannot read or change protected data, including through direct API requests.

---

## 18. Out of scope for the MVP

Do not implement these initially unless a small abstraction is needed for future support:

- Qualified electronic signatures.
- Payments.
- Video or voice calls.
- Call transcription.
- A marketplace for lawyers or mediators.
- Blockchain.
- Microservices.
- Native iOS or Android apps; a responsive Angular PWA is sufficient.
- AI arbitration, binding judgment, or autonomous final decisions.

---

## 19. Quality rules and final report

- Use consistent English domain terminology in code and localized text in the UI.
- Use types and enums instead of free-form strings for states, roles, origins, and visibility scopes.
- Do not leave any endpoint without an authorization test.
- Never accidentally send private data through WebSocket events or a shared LLM context.
- AI is never the authority that creates human approval.
- Every critical action must be explainable through the timeline and audit trail.
- Every critical screen must work on mobile and be keyboard accessible.
- Include demo data and a documented demo scenario.

After implementation, run all tests and builds and provide a concise final report containing:

1. Implemented architecture.
2. Exact run instructions.
3. Demo users, if created.
4. Complete demo flow.
5. Test and build results.
6. Known limitations.
7. Recommended next steps.

Begin now by inspecting the repository and presenting the architecture and implementation plan. Do not skip or weaken the trust, privacy, authorization, and audit model; these are the core of the product.
