import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { VisibilityScope } from './models';

export interface FileView {
  id: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
  kind: 'IMAGE' | 'DOCUMENT';
  status: 'PRIVATE' | 'SHARED' | 'WITHDRAWN';
  scope: string | null;
  ownerUserId: string;
  ownerDisplayName: string;
  mine: boolean;
  createdAt: string;
  sharedAt: string | null;
}

@Injectable({ providedIn: 'root' })
export class FilesService {
  private readonly http = inject(HttpClient);

  list(roomId: string): Observable<FileView[]> {
    return this.http.get<FileView[]>(`/api/v1/rooms/${roomId}/files`);
  }

  upload(roomId: string, file: File): Observable<FileView> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<FileView>(`/api/v1/rooms/${roomId}/files`, form);
  }

  share(roomId: string, fileId: string, scope: VisibilityScope, recipientParticipantIds?: string[]): Observable<FileView> {
    return this.http.post<FileView>(`/api/v1/rooms/${roomId}/files/${fileId}/share`, {
      scope,
      recipientParticipantIds: recipientParticipantIds ?? null,
    });
  }

  withdraw(roomId: string, fileId: string): Observable<void> {
    return this.http.post<void>(`/api/v1/rooms/${roomId}/files/${fileId}/withdraw`, {});
  }

  deletePrivate(roomId: string, fileId: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/rooms/${roomId}/files/${fileId}`);
  }

  /** Fetches with the auth header and hands back an object URL (img/src and downloads). */
  contentUrl(roomId: string, fileId: string): Promise<string | null> {
    return new Promise((resolve) => {
      this.http.get(`/api/v1/rooms/${roomId}/files/${fileId}/content`, { responseType: 'blob' }).subscribe({
        next: (blob) => resolve(URL.createObjectURL(blob)),
        error: () => resolve(null),
      });
    });
  }
}

/** Avatar loading with a per-user cache; null = no picture (UI shows the initial). */
@Injectable({ providedIn: 'root' })
export class AvatarService {
  private readonly http = inject(HttpClient);
  private readonly cache = new Map<string, Promise<string | null>>();

  url(userId: string): Promise<string | null> {
    let cached = this.cache.get(userId);
    if (!cached) {
      cached = new Promise((resolve) => {
        this.http.get(`/api/v1/users/${userId}/avatar`, { responseType: 'blob' }).subscribe({
          next: (blob) => resolve(URL.createObjectURL(blob)),
          error: () => resolve(null),
        });
      });
      this.cache.set(userId, cached);
    }
    return cached;
  }

  uploadMine(file: File): Observable<void> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<void>('/api/v1/users/me/avatar', form);
  }

  invalidate(userId: string): void {
    this.cache.delete(userId);
  }
}
