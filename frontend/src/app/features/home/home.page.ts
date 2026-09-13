import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { I18nService } from '../../core/i18n.service';
import { JoinRequestView, RoomResponse } from '../../core/models';
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
        @for (room of rooms(); track room.id) {
          <a class="card room-card" [routerLink]="['/rooms', room.id]">
            <strong>{{ room.title }}</strong>
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
  private readonly router = inject(Router);

  protected readonly rooms = signal<RoomResponse[]>([]);
  protected readonly loading = signal(true);
  protected readonly myRequests = signal<JoinRequestView[]>([]);
  protected readonly joinBusy = signal(false);
  protected readonly joinMessage = signal<string | null>(null);
  protected joinCode = '';

  constructor() {
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
        this.joinMessage.set(this.i18n.t('join.requested'));
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
