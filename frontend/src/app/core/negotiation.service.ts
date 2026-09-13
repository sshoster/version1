import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AgentProfile, NegotiationRunView } from './models';

@Injectable({ providedIn: 'root' })
export class NegotiationService {
  private readonly http = inject(HttpClient);

  profile(roomId: string): Observable<AgentProfile> {
    return this.http.get<AgentProfile>(`/api/v1/rooms/${roomId}/agent-profile`);
  }

  saveProfile(roomId: string, profile: AgentProfile): Observable<AgentProfile> {
    return this.http.put<AgentProfile>(`/api/v1/rooms/${roomId}/agent-profile`, profile);
  }

  start(roomId: string): Observable<{ runId: string }> {
    return this.http.post<{ runId: string }>(
      `/api/v1/rooms/${roomId}/negotiation-runs`,
      {},
      { headers: { 'Idempotency-Key': crypto.randomUUID() } },
    );
  }

  runs(roomId: string, limit = 1): Observable<NegotiationRunView[]> {
    return this.http.get<NegotiationRunView[]>(`/api/v1/rooms/${roomId}/negotiation-runs?limit=${limit}`);
  }

  pause(roomId: string, runId: string): Observable<void> {
    return this.http.post<void>(`/api/v1/rooms/${roomId}/negotiation-runs/${runId}/pause`, {});
  }

  resume(roomId: string, runId: string): Observable<void> {
    return this.http.post<void>(`/api/v1/rooms/${roomId}/negotiation-runs/${runId}/resume`, {});
  }

  answer(roomId: string, questionId: string, text: string): Observable<void> {
    return this.http.post<void>(`/api/v1/rooms/${roomId}/questions/${questionId}/answer`, { text });
  }
}
