import { Injectable, signal } from '@angular/core';

export type RoomTool = 'invite' | 'files' | 'timeline';

export interface ActiveRoomInfo {
  roomId: string;
  isOwner: boolean;
  canUpload: boolean;
}

/**
 * Bridges the app shell and the currently open discussion: while a room page is mounted it
 * publishes itself here, the hamburger drawer shows the room tools (invite / files / timeline),
 * and picking one opens the matching slide-in panel rendered by the room page.
 */
@Injectable({ providedIn: 'root' })
export class RoomUiService {
  readonly activeRoom = signal<ActiveRoomInfo | null>(null);
  readonly openTool = signal<RoomTool | null>(null);

  enter(info: ActiveRoomInfo): void {
    this.activeRoom.set(info);
  }

  leave(): void {
    this.activeRoom.set(null);
    this.openTool.set(null);
  }
}
