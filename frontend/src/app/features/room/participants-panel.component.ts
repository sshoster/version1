import { Component, DestroyRef, inject, input, signal } from '@angular/core';
import { I18nService } from '../../core/i18n.service';
import { InvitationSummary, ParticipantResponse, PresenceEntry } from '../../core/models';
import { RoomsService } from '../../core/rooms.service';
import { AvatarComponent } from '../../shared/avatar.component';

interface ParticipantRow {
  userId: string;
  displayName: string;
  roles: string;
  status: 'online' | 'recent' | 'offline';
  lastSeenAt: string | null;
}

/** Side panel: who takes part, with live presence (🟢 online / 🟡 recently active / ⚪ offline). */
@Component({
  selector: 'app-participants-panel',
  imports: [AvatarComponent],
  template: `
    <div class="card stack panel">
      <h2>{{ i18n.t('presence.title') }}</h2>
      <ul class="list">
        @for (row of rows(); track row.displayName) {
          <li>
            <span class="avatar-wrap">
              <app-avatar [userId]="row.userId" [name]="row.displayName" [size]="34" />
              <span class="dot" [class]="'dot ' + row.status" [attr.aria-label]="statusLabel(row.status)"></span>
            </span>
            <span class="info">
              <strong>{{ row.displayName }}</strong>
              <span class="muted small">{{ row.roles }}</span>
              <span class="muted small">{{ statusLabel(row.status) }}</span>
            </span>
          </li>
        }
        @for (pending of pendingInvitations(); track pending.id) {
          <li class="pending">
            <span class="dot waiting" aria-hidden="true">⏳</span>
            <span class="info">
              <strong>{{ pending.invitedName || pending.email || i18n.t('room.role.' + pending.role) }}</strong>
              <span class="muted small">{{ i18n.t('presence.pending') }}</span>
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
    .info { display: flex; flex-direction: column; }
    .small { font-size: 0.78rem; }
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
  `,
})
export class ParticipantsPanelComponent {
  protected readonly i18n = inject(I18nService);
  private readonly rooms = inject(RoomsService);
  private readonly destroyRef = inject(DestroyRef);

  readonly roomId = input.required<string>();
  readonly isOwner = input.required<boolean>();

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
        userId: participant.userId,
        displayName: participant.displayName,
        roles: participant.roles.map((role) => this.i18n.t(`room.role.${role}`)).join(', '),
        status,
        lastSeenAt: presence?.lastSeenAt ?? null,
      };
    });
  }

  protected statusLabel(status: ParticipantRow['status']): string {
    return this.i18n.t(`presence.${status}`);
  }
}
