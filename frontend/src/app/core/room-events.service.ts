import { Injectable, NgZone, inject } from '@angular/core';
import { Client, IMessage } from '@stomp/stompjs';
import { Observable, Subject, filter } from 'rxjs';
import { AuthService } from './auth.service';
import { RoomEvent } from './models';
import { wsUrl } from './server-base';

/** Ping sent whenever a new in-app notification is stored for this user. */
export interface NotificationPing {
  roomId: string;
  type: string;
  eventId: string;
}

/**
 * Live room updates over STOMP: `/topic/rooms/{id}` for room-wide events and the personal
 * `/user/queue/room-events` for audience-scoped ones. Payloads carry IDs only — content is always
 * refetched through the authorized REST API.
 */
@Injectable({ providedIn: 'root' })
export class RoomEventsService {
  private readonly auth = inject(AuthService);
  private readonly zone = inject(NgZone);

  private client: Client | null = null;
  private readonly events$ = new Subject<RoomEvent>();
  private readonly notifications$ = new Subject<NotificationPing>();
  private subscribedRoomIds = new Set<string>();

  watchRoom(roomId: string): Observable<RoomEvent> {
    this.ensureConnected();
    if (!this.subscribedRoomIds.has(roomId)) {
      this.subscribedRoomIds.add(roomId);
      if (this.client?.connected) {
        this.subscribeRoomTopic(roomId);
      }
    }
    return this.events$.pipe(filter((event) => event.roomId === roomId));
  }

  /** Live pings for this user's new in-app notifications — one per stored notification. */
  watchNotifications(): Observable<NotificationPing> {
    this.ensureConnected();
    return this.notifications$.asObservable();
  }

  private ensureConnected(): void {
    if (this.client) return;
    const token = this.auth.accessToken();
    if (!token) return;

    const client = new Client({
      brokerURL: wsUrl(),
      connectHeaders: { Authorization: `Bearer ${this.auth.accessToken() ?? ''}` },
      reconnectDelay: 4000,
      beforeConnect: () => {
        // Refresh the header on every (re)connect attempt — tokens rotate.
        client.connectHeaders = { Authorization: `Bearer ${this.auth.accessToken() ?? ''}` };
      },
      onConnect: () => {
        client.subscribe('/user/queue/room-events', (message) => this.emit(message));
        client.subscribe('/user/queue/notifications', (message) => this.emitNotification(message));
        this.subscribedRoomIds.forEach((roomId) => this.subscribeRoomTopic(roomId));
      },
    });
    this.client = client;
    client.activate();
  }

  private subscribeRoomTopic(roomId: string): void {
    this.client?.subscribe(`/topic/rooms/${roomId}`, (message) => this.emit(message));
  }

  private emit(message: IMessage): void {
    try {
      const event = JSON.parse(message.body) as RoomEvent;
      this.zone.run(() => this.events$.next(event));
    } catch {
      // Malformed frame: ignore.
    }
  }

  private emitNotification(message: IMessage): void {
    try {
      const ping = JSON.parse(message.body) as NotificationPing;
      this.zone.run(() => this.notifications$.next(ping));
    } catch {
      // Malformed frame: ignore.
    }
  }
}
