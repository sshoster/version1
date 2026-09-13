import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApprovalRequestView, ProposalView, TimelineEntry } from './models';

export interface ProposalContent {
  title: string;
  terms: string[];
  assumptions: string[];
  sourceRunId?: string | null;
}

@Injectable({ providedIn: 'root' })
export class ProposalsService {
  private readonly http = inject(HttpClient);

  list(roomId: string): Observable<ProposalView[]> {
    return this.http.get<ProposalView[]>(`/api/v1/rooms/${roomId}/proposals`);
  }

  create(roomId: string, content: ProposalContent): Observable<ProposalView> {
    return this.http.post<ProposalView>(`/api/v1/rooms/${roomId}/proposals`, content);
  }

  revise(roomId: string, proposalId: string, content: ProposalContent): Observable<ProposalView> {
    return this.http.post<ProposalView>(`/api/v1/rooms/${roomId}/proposals/${proposalId}/revisions`, content);
  }

  requestApproval(roomId: string, proposalId: string): Observable<ProposalView> {
    return this.http.post<ProposalView>(`/api/v1/rooms/${roomId}/proposals/${proposalId}/request-approval`, {});
  }

  decide(
    roomId: string,
    requestId: string,
    decision: 'approve' | 'reject' | 'request-changes',
    expectedVersion: number,
    comment?: string,
  ): Observable<ApprovalRequestView> {
    return this.http.post<ApprovalRequestView>(
      `/api/v1/rooms/${roomId}/approval-requests/${requestId}/${decision}`,
      { expectedVersion, comment: comment ?? null },
      { headers: { 'Idempotency-Key': crypto.randomUUID() } },
    );
  }

  timeline(roomId: string): Observable<TimelineEntry[]> {
    return this.http.get<TimelineEntry[]>(`/api/v1/rooms/${roomId}/timeline`);
  }
}
