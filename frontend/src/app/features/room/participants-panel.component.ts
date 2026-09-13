import { Component, DestroyRef, inject, input, signal } from '@angular/core';
import { I18nService } from '../../core/i18n.service';
import { InvitationSummary, ParticipantResponse, PresenceEntry } from '../../core/models';
import { RoomsService } from '../../core/rooms.service';
import { AvatarComponent } from '../../shared/avatar.component';

interface ParticipantRow {
  participantId: string;
  userId: string;
  displayName: string;
  roles: string;
  isAdmin: boolean;
  status: 'online' | 'recent' | 'offline';
  lastSeenAt: string | null;
}

/**
 * Who takes part, with live presence (🟢 online / 🟡 recently active / ⚪ offline).
 * Desktop: sticky side panel. Mobile: a compact horizontal avatar strip at the top of the page.
 * Admins can promote/demote other admins here (never the creator, never themselves).
 */
@Component({
  selector: 'app-participants-panel',
  imports: [AvatarComponent],
  template: `
    <div class="card stack panel">
      <h2>{{ i18n.t('presence.title') }}</h2>
      <ul class="list">
        @for (row of rows(); track row.participantId) {
          <li>
            <span class="avatar-wrap">
              <app-avatar [userId]="row.userId" [name]="row.displayName" [size]="34" />
              <span class="dot" [class]="'dot ' + row.status" [attr.aria-label]="statusLabel(row.status)"></span>
            </span>
            <span class="info">
              <strong class="name">{{ row.displayName }}{{ row.isAdmin ? ' ★' : '' }}</strong>
              <span class="muted small detail">{{ row.roles }}</span>
              <span class="muted small detail">{{ statusLabel(row.status) }}</span>
              @if (canToggleAdmin(row)) {
                <button class="btn btn-quiet mini detail" type="button" (click)="toggleAdmin(row)">
                  {{ row.isAdmin ? i18n.t('admin.removeAdmin') : i18n.t('admin.makeAdmin') }}
                </button>
              }
            </span>
          </li>
        }
        @for (pending of pendingInvitations(); track pending.id) {
          <li class="pending">
            <span class="dot waiting" aria-hidden="true">⏳</span>
            <span class="info">
              <strong class="name">{{ pending.invitedName || pending.email || i18n.t('room.role.' + pending.role) }}</strong>
              <span class="muted small detail">{{ i18n.t('presence.pending') }}</span>
            </span>
          </li>
        }
      </ul>
    </div>
  `,
  styles: `
    .panel h2 { font-size: 1rem; margin-block-end: var(--space-2); }
    .list { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--space-3); }
    .list li { display: flex; gap: var(--space-2); align-items: flex-start; }
    .info { display: flex; flex-direction: column; min-width: 0; }
    .small { font-size: 0.78rem; }
    .mini { min-height: 28px; padding: 0; font-size: 0.78rem; align-self: flex-start; }
    .avatar-wrap { position: relative; flex-shrink: 0; }
    .avatar-wrap .dot { position: absolute; inset-block-end: 0; inset-inline-end: -2px; border: 2px solid var(--color-surface); margin: 0; }
    .dot {
      inline-size: 12px; block-size: 12px; border-radius: 50%; margin-block-start: 6px;
      flex-shrink: 0; background: var(--color-border);
    }
    .dot.online { background: #2e9e5b; box-shadow: 0 0 0 3px rgba(46, 158, 91, 0.25); }
    .dot.recent { background: #d9a514; }
    .dot.offline { background: #c2c9d0; }
    .dot.waiting { background: transparent; font-size: 12px; line-height: 1; margin-block-start: 4px; }
    .pending { opacity: 0.8; }

    /* Mobile: compact horizontal avatar strip — avatars, presence dots, first names only. */
    @media (max-width: 999px) {
      .panel { padding: var(--space-2) var(--space-3); }
      .panel h2 { display: none; }
      .list { display: flex; flex-direction: row; gap: var(--space-3); overflow-x: auto; padding-block: var(--space-1); }
      .list li { flex-direction: column; align-items: center; gap: 4px; flex-shrink: 0; max-width: 72px; }
      .info { align-items: center; }
      .name { font-size: 0.72rem; font-weight: 500; max-width: 68px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      .detail { display: none; }
    }
  `,
})
export class ParticipantsPanelComponent {
  protected readonly i18n = inject(I18nService);
  private readonly rooms = inject(RoomsService);
  private readonly destroyRef = inject(DestroyRef);

  readonly roomId = input.required<string>();
  readonly isOwner = input.required<boolean>();
  readonly creatorUserId = input<string>('');
  readonly myUserId = input<string>('');

  protected readonly rows = signal<ParticipantRow[]>([]);
  protected readonly pendingInvitations = signal<InvitationSummary[]>([]);

  private participants: ParticipantResponse[] = [];
  private interval: ReturnType<typeof setInterval> | null = null;

  ngOnInit(): void {
    this.refresh();
    // Fallback poll: "recently active" ages out even without presence events.
    this.interval = setInterval(() => this.refresh(), 60_000);
    this.destroyRef.onDestroy(() => {
      if (this.interval) clearInterval(this.interval);
    });
  }

  refresh(): void {
    this.rooms.participants(this.roomId()).subscribe({
      next: (participants) => {
        this.participants = participants;
        this.loadPresence();
      },
      error: () => undefined,
    });
    if (this.isOwner()) {
      this.rooms.invitations(this.roomId()).subscribe({
        next: (invitations) =>
          this.pendingInvitations.set(invitations.filter((invitation) => invitation.status === 'PENDING')),
        error: () => undefined,
      });
    }
  }

  protected canToggleAdmin(row: ParticipantRow): boolean {
    return this.isOwner() && row.userId !== this.myUserId() && row.userId !== this.creatorUserId();
  }

  protected toggleAdmin(row: ParticipantRow): void {
    const participant = this.participants.find((candidate) => candidate.id === row.participantId);
    if (!participant) return;
    const roles = row.isAdmin
      ? participant.roles.filter((role) => role !== 'OWNER')
      : [...participant.roles, 'OWNER' as const];
    this.rooms.updateParticipantRoles(this.roomId(), row.participantId, roles.length ? roles : ['PARTY']).subscribe({
      next: () => this.refresh(),
      error: () => this.refresh(),
    });
  }

  private loadPresence(): void {
    this.rooms.presence(this.roomId()).subscribe({
      next: (entries) => this.rows.set(this.merge(entries)),
      error: () => this.rows.set(this.merge([])),
    });
  }

  private merge(entries: PresenceEntry[]): ParticipantRow[] {
    const byUser = new Map(entries.map((entry) => [entry.userId, entry]));
    const tenMinutesAgo = Date.now() - 10 * 60 * 1000;
    return this.participants.map((participant) => {
      const presence = byUser.get(participant.userId);
      const status: ParticipantRow['status'] = presence?.online
        ? 'online'
        : presence?.lastSeenAt && Date.parse(presence.lastSeenAt) > tenMinutesAgo
          ? 'recent'
          : 'offline';
      return {
        participantId: participant.id,
        userId: participant.userId,
        displayName: participant.displayName,
        roles: participant.roles.map((role) => this.i18n.t(`room.role.${role}`)).join(', '),
        isAdmin: participant.roles.includes('OWNER'),
        status,
        lastSeenAt: presence?.lastSeenAt ?? null,
      };
    });
  }

  protected statusLabel(status: ParticipantRow['status']): string {
    return this.i18n.t(`presence.${status}`);
  }
}
