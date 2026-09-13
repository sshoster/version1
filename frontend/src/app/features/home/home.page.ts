import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { I18nService } from '../../core/i18n.service';
import { RoomResponse } from '../../core/models';
import { RoomsService } from '../../core/rooms.service';

@Component({
  selector: 'app-home-page',
  imports: [RouterLink, DatePipe],
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
  `,
})
export class HomePage {
  protected readonly i18n = inject(I18nService);
  private readonly roomsService = inject(RoomsService);
  private readonly router = inject(Router);

  protected readonly rooms = signal<RoomResponse[]>([]);
  protected readonly loading = signal(true);

  constructor() {
    this.roomsService.list().subscribe({
      next: (rooms) => {
        this.rooms.set(sortForAttention(rooms));
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
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
