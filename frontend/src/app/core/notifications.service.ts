import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface RoomUnread {
  roomId: string;
  unread: number;
}

/** Per-room unread activity — powers the badges on the home page. */
@Injectable({ providedIn: 'root' })
export class NotificationsService {
  private readonly http = inject(HttpClient);

  unreadByRoom(): Observable<RoomUnread[]> {
    return this.http.get<RoomUnread[]>('/api/v1/notifications/unread-by-room');
  }

  /** Called once the room's content has actually rendered, so the badge clears honestly. */
  markRoomRead(roomId: string): Observable<void> {
    return this.http.post<void>(`/api/v1/notifications/rooms/${roomId}/read`, null);
  }
}
