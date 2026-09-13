import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  InvitationCreated,
  InvitationPublicInfo,
  InvitationSummary,
  ParticipantResponse,
  ParticipantRole,
  PresenceEntry,
  RoomResponse,
} from './models';

@Injectable({ providedIn: 'root' })
export class RoomsService {
  private readonly http = inject(HttpClient);

  list(): Observable<RoomResponse[]> {
    return this.http.get<RoomResponse[]>('/api/v1/rooms');
  }

  get(roomId: string): Observable<RoomResponse> {
    return this.http.get<RoomResponse>(`/api/v1/rooms/${roomId}`);
  }

  create(title: string, objective: string | null): Observable<RoomResponse> {
    return this.http.post<RoomResponse>('/api/v1/rooms', { title, objective });
  }

  participants(roomId: string): Observable<ParticipantResponse[]> {
    return this.http.get<ParticipantResponse[]>(`/api/v1/rooms/${roomId}/participants`);
  }

  invite(
    roomId: string,
    role: ParticipantRole,
    email: string | null,
    firstName: string | null = null,
    lastName: string | null = null,
  ): Observable<InvitationCreated> {
    const idempotencyKey = crypto.randomUUID();
    return this.http.post<InvitationCreated>(
      `/api/v1/rooms/${roomId}/invitations`,
      { role, email, firstName, lastName },
      { headers: { 'Idempotency-Key': idempotencyKey } },
    );
  }

  invitations(roomId: string): Observable<InvitationSummary[]> {
    return this.http.get<InvitationSummary[]>(`/api/v1/rooms/${roomId}/invitations`);
  }

  presence(roomId: string): Observable<PresenceEntry[]> {
    return this.http.get<PresenceEntry[]>(`/api/v1/rooms/${roomId}/presence`);
  }

  invitationInfo(token: string): Observable<InvitationPublicInfo> {
    return this.http.get<InvitationPublicInfo>(`/api/v1/invitations/${token}`);
  }

  acceptInvitation(token: string): Observable<{ roomId: string }> {
    return this.http.post<{ roomId: string }>(`/api/v1/invitations/${token}/accept`, {});
  }
}
