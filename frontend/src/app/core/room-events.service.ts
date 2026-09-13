import { Injectable, NgZone, inject } from '@angular/core';
import { Client, IMessage } from '@stomp/stompjs';
import { Observable, Subject, filter } from 'rxjs';
import { AuthService } from './auth.service';
import { RoomEvent } from './models';

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

  private ensureConnected(): void {
    if (this.client) return;
    const token = this.auth.accessToken();
    if (!token) return;

    const protocol = location.protocol === 'https:' ? 'wss' : 'ws';
    const client = new Client({
      brokerURL: `${protocol}://${location.host}/ws`,
      connectHeaders: { Authorization: `Bearer ${this.auth.accessToken() ?? ''}` },
      reconnectDelay: 4000,
      beforeConnect: () => {
        // Refresh the header on every (re)connect attempt — tokens rotate.
        client.connectHeaders = { Authorization: `Bearer ${this.auth.accessToken() ?? ''}` };
      },
      onConnect: () => {
        client.subscribe('/user/queue/room-events', (message) => this.emit(message));
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
}
