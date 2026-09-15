import { Injectable, inject, signal } from '@angular/core';
import { RoomResponse } from './models';
import { RoomsService } from './rooms.service';

/**
 * Client-side cache of the user's rooms, shared by the home page and the hamburger drawer.
 * Stale-while-revalidate: consumers render the cached list instantly; a fetch happens only when
 * the cache is older than the TTL (or explicitly invalidated), not on every page visit.
 */
@Injectable({ providedIn: 'root' })
export class RoomsStore {
  private readonly roomsService = inject(RoomsService);

  readonly rooms = signal<RoomResponse[]>([]);
  readonly loaded = signal(false);

  private fetchedAt = 0;
  private inflight = false;
  private static readonly TTL_MS = 60_000;

  /** Renders from cache when fresh; refetches only past the TTL. */
  ensureFresh(): void {
    if (this.inflight) return;
    if (this.loaded() && Date.now() - this.fetchedAt < RoomsStore.TTL_MS) return;
    this.reload();
  }

  /** Unconditional refresh (e.g. a live event told us something changed). */
  reload(): void {
    if (this.inflight) return;
    this.inflight = true;
    this.roomsService.list().subscribe({
      next: (rooms) => {
        this.rooms.set(rooms);
        this.loaded.set(true);
        this.fetchedAt = Date.now();
        this.inflight = false;
      },
      error: () => {
        this.inflight = false;
        this.loaded.set(true); // stop spinners; cache (possibly empty) remains
      },
    });
  }

  /** Marks the cache stale so the next ensureFresh() refetches (e.g. after creating a room). */
  invalidate(): void {
    this.fetchedAt = 0;
  }

  /** On sign-out: never leak one user's rooms into the next session. */
  clear(): void {
    this.rooms.set([]);
    this.loaded.set(false);
    this.fetchedAt = 0;
  }
}
