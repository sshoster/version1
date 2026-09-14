// API types mirroring the backend DTOs (backend/src/main/kotlin/.../api).

export type ParticipantRole = 'OWNER' | 'PARTY' | 'ADVISOR' | 'OBSERVER' | 'FACILITATOR';

export type RoomStatus =
  | 'DRAFT'
  | 'INVITING'
  | 'INTAKE'
  | 'ACTIVE'
  | 'WAITING_FOR_USER'
  | 'PROPOSAL_READY'
  | 'AGREEMENT_PENDING_APPROVAL'
  | 'AGREED'
  | 'PAUSED'
  | 'CLOSED'
  | 'ARCHIVED';

export type InvitationStatus = 'PENDING' | 'ACCEPTED' | 'REVOKED' | 'EXPIRED';

export interface AdminUserView {
  id: string;
  email: string;
  displayName: string;
  suspended: boolean;
  createdAt: string;
}

export interface AdminRoomView {
  id: string;
  title: string;
  status: string;
  joinCode: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface UserResponse {
  id: string;
  email: string;
  displayName: string;
  superAdmin?: boolean;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  user: UserResponse;
}

export interface RoomResponse {
  id: string;
  title: string;
  objective: string | null;
  status: RoomStatus;
  ownerUserId: string;
  joinCode: string | null;
  myRoles: ParticipantRole[];
  createdAt: string;
  updatedAt: string;
}

export interface JoinRequestView {
  id: string;
  roomId: string;
  roomTitle: string;
  displayName: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
  createdAt: string;
}

export interface ParticipantResponse {
  id: string;
  userId: string;
  displayName: string;
  roles: ParticipantRole[];
  status: 'ACTIVE' | 'REMOVED';
  joinedAt: string;
}

export interface InvitationCreated {
  invitationId: string;
  roomId: string;
  role: ParticipantRole;
  email: string | null;
  invitedName: string | null;
  expiresAt: string;
  token: string;
  acceptUrl: string;
  emailSent: boolean;
}

export interface InvitationSummary {
  id: string;
  role: ParticipantRole;
  email: string | null;
  invitedName: string | null;
  status: InvitationStatus;
  expiresAt: string;
  createdAt: string;
}

export interface InvitationPublicInfo {
  roomTitle: string;
  role: ParticipantRole;
  status: InvitationStatus;
  expiresAt: string;
  invitedBy: string;
  invitedName: string | null;
}

export interface PresenceEntry {
  userId: string;
  online: boolean;
  lastSeenAt: string | null;
}

export type VisibilityScope =
  | 'PRIVATE_TO_AUTHOR_AND_AI'
  | 'MY_ADVISORS'
  | 'SELECTED_PARTICIPANTS'
  | 'ALL_PARTIES'
  | 'ALL_ROOM_PARTICIPANTS';

export type ContentOrigin =
  | 'USER_AUTHORED'
  | 'AI_DRAFT_ACCEPTED'
  | 'AI_DRAFT_USER_EDITED'
  | 'SYSTEM_GENERATED'
  | 'ADVISOR_AUTHORED';

export interface PrivateMessage {
  id: string;
  sender: 'USER' | 'ASSISTANT';
  text: string;
  sourceMessageId: string | null;
  createdAt: string;
}

export interface Recipient {
  participantId: string;
  displayName: string;
  roles: ParticipantRole[];
}

export interface SharePreviewResult {
  previewId: string;
  text: string;
  contentType: 'TEXT';
  scope: VisibilityScope;
  origin: ContentOrigin;
  recipients: Recipient[];
  expiresAt: string;
}

export interface SharedItem {
  id: string;
  version: number;
  text: string | null;
  origin: ContentOrigin;
  authorUserId: string;
  authorDisplayName: string;
  scope: VisibilityScope;
  status: 'ACTIVE' | 'WITHDRAWN';
  mine: boolean;
  createdAt: string;
  versionCreatedAt: string;
}

export interface RoomEvent {
  eventId: string;
  roomId: string;
  type: string;
  occurredAt: string;
  resourceId: string | null;
  resourceVersion: number | null;
}

export type RunStatus = 'RUNNING' | 'WAITING_FOR_USER' | 'PAUSED' | 'COMPLETED' | 'FAILED';

export type RunStopReason =
  | 'NONE'
  | 'MISSING_INFO'
  | 'NEW_CONCESSION'
  | 'SENSITIVE_DISCLOSURE'
  | 'POSSIBLE_AGREEMENT'
  | 'DEADLOCK'
  | 'MAX_TURNS'
  | 'SAFETY'
  | 'BUDGET_EXCEEDED'
  | 'PROVIDER_ERROR';

export interface TurnProposal {
  title: string;
  terms: string[];
  assumptions: string[];
  openIssues: string[];
}

export interface RoundResult {
  agreedPoints: string[];
  unresolvedPoints: string[];
  proposalsConsidered: string[];
  assumptions: string[];
  recommendedProposal: TurnProposal | null;
  stopReason: RunStopReason;
}

export interface NegotiationTurnView {
  turnNumber: number;
  partyDisplayName: string;
  publicMessage: string | null;
  proposal: TurnProposal | null;
  questionsForOtherParty: string[];
  createdAt: string;
}

export interface QuestionView {
  id: string;
  text: string;
  /** One-tap answer suggestions offered by the assistant. */
  options: string[];
  status: 'OPEN' | 'ANSWERED';
  createdAt: string;
}

export interface NegotiationRunView {
  id: string;
  status: RunStatus;
  stopReason: RunStopReason;
  turnCount: number;
  maxTurns: number;
  result: RoundResult | null;
  turns: NegotiationTurnView[];
  myOpenQuestions: QuestionView[];
  /** Who still owes their assistant an answer — user IDs only, never question content. */
  pendingAnswerUserIds: string[];
  createdAt: string;
  updatedAt: string;
}

export type ProposalStatus = 'OPEN' | 'AGREED' | 'CLOSED';
export type ApprovalRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'SUPERSEDED';
export type ApprovalDecision = 'APPROVED' | 'REJECTED' | 'CHANGES_REQUESTED';

export interface ApprovalView {
  userId: string;
  userDisplayName: string;
  decision: ApprovalDecision;
  comment: string | null;
  proposalVersion: number;
  createdAt: string;
}

export interface ApprovalRequestView {
  id: string;
  proposalId: string;
  proposalVersion: number;
  status: ApprovalRequestStatus;
  requiredUserIds: string[];
  approvals: ApprovalView[];
  myDecision: ApprovalDecision | null;
  createdAt: string;
}

export interface ProposalVersionView {
  version: number;
  title: string;
  terms: string[];
  assumptions: string[];
  createdByDisplayName: string;
  superseded: boolean;
  createdAt: string;
}

export interface ProposalView {
  id: string;
  status: ProposalStatus;
  currentVersion: number;
  current: ProposalVersionView;
  versions: ProposalVersionView[];
  pendingRequest: ApprovalRequestView | null;
  createdAt: string;
}

export interface TimelineEntry {
  id: string;
  action: string;
  actorType: 'USER' | 'AI' | 'ADVISOR' | 'SYSTEM';
  actorDisplayName: string | null;
  targetType: string | null;
  targetId: string | null;
  metadata: Record<string, string>;
  occurredAt: string;
}

export type OutcomeType = 'DISCUSSION_SUMMARY' | 'APPROVED_UNDERSTANDINGS' | 'AGREEMENT_DRAFT';

export interface ApprovalRecord {
  userId: string;
  displayName: string;
  approvedAt: string;
  proposalVersion: number;
  contentHash: string;
}

export interface UnderstandingBlock {
  proposalId: string;
  title: string;
  terms: string[];
  assumptions: string[];
  proposalVersion: number;
  contentHash: string;
  approvals: ApprovalRecord[];
}

export interface OutcomeView {
  id: string;
  type: OutcomeType;
  version: number;
  aiGenerated: boolean;
  draftOnly: boolean;
  notLegalAdvice: boolean;
  text: string | null;
  understandings: UnderstandingBlock[] | null;
  llmProvider: string | null;
  createdAt: string;
}

export interface AgentProfile {
  goals: string;
  boundaries: string;
  flexibility: string;
}

export interface ApiError {
  code: string;
  message: string;
  correlationId?: string;
  errorId?: string;
  fieldErrors?: Record<string, string>;
}
