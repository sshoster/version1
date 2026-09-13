import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  ContentOrigin,
  PrivateMessage,
  SharePreviewResult,
  SharedItem,
  VisibilityScope,
} from './models';

export interface ShareRequestBody {
  text: string;
  scope: VisibilityScope;
  recipientParticipantIds?: string[] | null;
  origin: ContentOrigin;
  sourceDraftId?: string | null;
}

@Injectable({ providedIn: 'root' })
export class MessagingService {
  private readonly http = inject(HttpClient);

  privateMessages(roomId: string): Observable<PrivateMessage[]> {
    return this.http.get<PrivateMessage[]>(`/api/v1/rooms/${roomId}/private-messages`);
  }

  writePrivateMessage(roomId: string, text: string): Observable<PrivateMessage> {
    return this.http.post<PrivateMessage>(`/api/v1/rooms/${roomId}/private-messages`, { text });
  }

  requestAiDraft(roomId: string, messageId: string): Observable<PrivateMessage> {
    return this.http.post<PrivateMessage>(
      `/api/v1/rooms/${roomId}/private-messages/${messageId}/ai-draft`,
      {},
    );
  }

  sharePreview(roomId: string, body: ShareRequestBody): Observable<SharePreviewResult> {
    return this.http.post<SharePreviewResult>(`/api/v1/rooms/${roomId}/share-previews`, body);
  }

  publish(roomId: string, previewId: string, body: ShareRequestBody, itemId?: string): Observable<SharedItem> {
    return this.http.post<SharedItem>(
      `/api/v1/rooms/${roomId}/shared-items`,
      { ...body, previewId, itemId: itemId ?? null },
      { headers: { 'Idempotency-Key': crypto.randomUUID() } },
    );
  }

  sharedItems(roomId: string): Observable<SharedItem[]> {
    return this.http.get<SharedItem[]>(`/api/v1/rooms/${roomId}/shared-items`);
  }

  withdraw(roomId: string, itemId: string): Observable<void> {
    return this.http.post<void>(`/api/v1/rooms/${roomId}/shared-items/${itemId}/withdraw`, {});
  }
}
