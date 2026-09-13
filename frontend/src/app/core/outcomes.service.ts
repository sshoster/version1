import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { OutcomeView } from './models';

@Injectable({ providedIn: 'root' })
export class OutcomesService {
  private readonly http = inject(HttpClient);

  list(roomId: string): Observable<OutcomeView[]> {
    return this.http.get<OutcomeView[]>(`/api/v1/rooms/${roomId}/outcomes`);
  }

  generateSummary(roomId: string): Observable<OutcomeView> {
    return this.http.post<OutcomeView>(`/api/v1/rooms/${roomId}/outcomes/summary`, {});
  }

  generateUnderstandings(roomId: string): Observable<OutcomeView> {
    return this.http.post<OutcomeView>(`/api/v1/rooms/${roomId}/outcomes/approved-understandings`, {});
  }

  generateDraft(roomId: string): Observable<OutcomeView> {
    return this.http.post<OutcomeView>(`/api/v1/rooms/${roomId}/outcomes/agreement-draft`, {});
  }

  pauseRoom(roomId: string): Observable<unknown> {
    return this.http.post(`/api/v1/rooms/${roomId}/pause`, {});
  }

  resumeRoom(roomId: string): Observable<unknown> {
    return this.http.post(`/api/v1/rooms/${roomId}/resume`, {});
  }

  closeRoom(roomId: string): Observable<unknown> {
    return this.http.post(`/api/v1/rooms/${roomId}/close`, {});
  }

  reopenRoom(roomId: string): Observable<unknown> {
    return this.http.post(`/api/v1/rooms/${roomId}/reopen`, {});
  }
}
