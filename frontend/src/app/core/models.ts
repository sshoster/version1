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

export interface UserResponse {
  id: string;
  email: string;
  displayName: string;
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
  myRoles: ParticipantRole[];
  createdAt: string;
  updatedAt: string;
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
  expiresAt: string;
  token: string;
  acceptUrl: string;
  emailSent: boolean;
}

export interface InvitationSummary {
  id: string;
  role: ParticipantRole;
  email: string | null;
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

export interface ApiError {
  code: string;
  message: string;
  correlationId?: string;
  errorId?: string;
  fieldErrors?: Record<string, string>;
}
