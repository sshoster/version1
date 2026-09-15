import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { I18nService } from '../../core/i18n.service';
import { JoinRequestView, RoomResponse } from '../../core/models';
import { NotificationsService } from '../../core/notifications.service';
import { RoomEventsService } from '../../core/room-events.service';
import { RoomsService } from '../../core/rooms.service';

@Component({
  selector: 'app-home-page',
  imports: [RouterLink, DatePipe, FormsModule],
  template: `
    <div class="page stack">
      <h1>{{ i18n.t('home.title') }}</h1>

      @if (loading()) {
        <p class="muted">{{ i18n.t('common.loading') }}</p>
      } @else if (rooms().length === 0) {
        <div class="card stack" style="text-align: center">
          <h2>{{ i18n.t('home.empty.title') }}</h2>
          <p class="muted">{{ i18n.t('home.empty.hint') }}</p>
          <a class="btn btn-primary" routerLink="/new">{{ i18n.t('home.start') }}</a>
        </div>
      } @else {
        @for (room of sortedRooms(); track room.id) {
          <a class="card room-card" [class.has-unread]="unreadFor(room.id) > 0" [routerLink]="['/rooms', room.id]">
            <span class="title-row">
              <strong>{{ room.title }}</strong>
              @if (unreadFor(room.id) > 0) {
                <span class="unread-badge" [attr.aria-label]="i18n.t('home.unread')">
                  {{ unreadFor(room.id) > 99 ? '99+' : unreadFor(room.id) }}
                </span>
              }
            </span>
            <span class="badge">{{ i18n.t('status.' + room.status) }}</span>
            <span class="muted small">{{ i18n.t('home.updated') }} {{ room.updatedAt | date: 'short' }}</span>
          </a>
        }
        <a class="btn btn-primary" routerLink="/new">{{ i18n.t('home.start') }}</a>
      }

      @for (pending of myRequests(); track pending.id) {
        <p class="badge pending-chip">⏳ {{ i18n.t('join.pendingFor', pending.roomTitle) }}</p>
      }

      <form class="card join-card" (ngSubmit)="joinByCode()">
        <label for="joinCode"><strong>{{ i18n.t('join.haveCode') }}</strong></label>
        <div class="join-row">
          <input
            id="joinCode" name="joinCode" type="text" dir="ltr" maxlength="12"
            [placeholder]="i18n.t('join.codePlaceholder')" [(ngModel)]="joinCode"
          />
          <button class="btn btn-secondary" type="submit" [disabled]="joinBusy() || !joinCode.trim()">
            {{ i18n.t('join.send') }}
          </button>
        </div>
        @if (joinMessage()) {
          <p class="muted join-msg">{{ joinMessage() }}</p>
        }
      </form>
    </div>
  `,
  styles: `
    .room-card {
      display: flex;
      flex-direction: column;
      gap: var(--space-1);
      text-decoration: none;
      color: inherit;
    }
    .room-card:hover { border-color: var(--color-primary); }
    .room-card.has-unread { border-inline-start: 4px solid var(--color-primary); }
    .title-row { display: flex; align-items: center; gap: var(--space-2); }
    .unread-badge {
      background: var(--color-danger); color: #fff; border-radius: 999px;
      min-width: 22px; height: 22px; padding: 0 6px; font-size: 0.78rem; font-weight: 700;
      display: inline-flex; align-items: center; justify-content: center;
    }
    .small { font-size: 0.8rem; }
    .badge { align-self: flex-start; }
    .pending-chip { margin: 0; }
    .join-card { display: flex; flex-direction: column; gap: var(--space-2); }
    .join-row { display: flex; gap: var(--space-2); }
    .join-row input { flex: 1; text-transform: uppercase; }
    .join-msg { margin: 0; }
  `,
})
export class HomePage {
  protected readonly i18n = inject(I18nService);
  private readonly roomsService = inject(RoomsService);
  private readonly notifications = inject(NotificationsService);
  private readonly roomEvents = inject(RoomEventsService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly rooms = signal<RoomResponse[]>([]);
  protected readonly loading = signal(true);
  protected readonly myRequests = signal<JoinRequestView[]>([]);
  protected readonly unread = signal<Map<string, number>>(new Map());
  protected readonly joinBusy = signal(false);
  protected readonly joinMessage = signal<string | null>(null);
  protected joinCode = '';

  /** Rooms with unread activity float to the top of their attention group. */
  protected readonly sortedRooms = computed(() => {
    const unread = this.unread();
    return [...this.rooms()].sort((a, b) => {
      const aUnread = (unread.get(a.id) ?? 0) > 0 ? 0 : 1;
      const bUnread = (unread.get(b.id) ?? 0) > 0 ? 0 : 1;
      return aUnread - bUnread;
    });
  });

  constructor() {
    // A shared join link (/?code=XXXXXX) lands here with the code prefilled — one tap to request.
    const linkedCode = this.route.snapshot.queryParamMap.get('code');
    if (linkedCode) this.joinCode = linkedCode.toUpperCase();

    this.roomsService.list().subscribe({
      next: (rooms) => {
        this.rooms.set(sortForAttention(rooms));
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
    this.roomsService.myJoinRequests().subscribe({
      next: (requests) => this.myRequests.set(requests),
      error: () => undefined,
    });
    this.notifications.unreadByRoom().subscribe({
      next: (entries) => this.unread.set(new Map(entries.map((entry) => [entry.roomId, entry.unread]))),
      error: () => undefined,
    });
    // Live: each stored notification is pinged over WebSocket — badge appears without a refresh.
    this.roomEvents
      .watchNotifications()
      .pipe(takeUntilDestroyed())
      .subscribe((ping) => {
        this.unread.update((current) => {
          const next = new Map(current);
          next.set(ping.roomId, (next.get(ping.roomId) ?? 0) + 1);
          return next;
        });
        // Activity in a room we don't list yet (e.g. a just-approved join) — reload the list.
        if (!this.rooms().some((room) => room.id === ping.roomId)) {
          this.roomsService.list().subscribe({
            next: (rooms) => this.rooms.set(sortForAttention(rooms)),
            error: () => undefined,
          });
        }
      });
  }

  protected unreadFor(roomId: string): number {
    return this.unread().get(roomId) ?? 0;
  }

  protected joinByCode(): void {
    const code = this.joinCode.trim();
    if (!code || this.joinBusy()) return;
    this.joinBusy.set(true);
    this.joinMessage.set(null);
    this.roomsService.joinByCode(code).subscribe({
      next: (request) => {
        this.joinBusy.set(false);
        this.joinCode = '';
        this.joinMessage.set(
          request.ownerName
            ? this.i18n.t('join.requestedTo', request.ownerName, request.ownerEmail)
            : this.i18n.t('join.requested'),
        );
        this.myRequests.update((current) =>
          current.some((r) => r.id === request.id) ? current : [request, ...current],
        );
      },
      error: (err: { status?: number; error?: { code?: string } }) => {
        this.joinBusy.set(false);
        if (err?.error?.code === 'ALREADY_MEMBER') {
          this.joinMessage.set(this.i18n.t('join.alreadyMember'));
        } else {
          this.joinMessage.set(this.i18n.t('join.notFound'));
        }
      },
    });
  }
}

/** "Needs your attention" first, then "waiting for the other person", newest first inside each group. */
function sortForAttention(rooms: RoomResponse[]): RoomResponse[] {
  const needsAttention = new Set(['WAITING_FOR_USER', 'PROPOSAL_READY', 'AGREEMENT_PENDING_APPROVAL', 'DRAFT', 'INTAKE']);
  return [...rooms].sort((a, b) => {
    const aFirst = needsAttention.has(a.status) ? 0 : 1;
    const bFirst = needsAttention.has(b.status) ? 0 : 1;
    if (aFirst !== bFirst) return aFirst - bFirst;
    return b.updatedAt.localeCompare(a.updatedAt);
  });
}
