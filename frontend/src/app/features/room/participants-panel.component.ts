import { Component, DestroyRef, computed, inject, input, signal } from '@angular/core';
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
 * Desktop: sticky side panel. Mobile: a floating button on the side that expands into a
 * floating, collapsible list of everyone. Admins can promote/demote other admins here
 * (never the creator, never themselves).
 */
@Component({
  selector: 'app-participants-panel',
  imports: [AvatarComponent],
  template: `
    <!-- Mobile only: floating toggle with the online count. -->
    <button
      class="fab" type="button"
      [attr.aria-label]="i18n.t('presence.title')" [attr.aria-expanded]="expanded()"
      (click)="expanded.set(!expanded())"
    >
      👥
      @if (onlineCount() > 0) {
        <span class="fab-badge">{{ onlineCount() }}</span>
      }
    </button>
    @if (expanded()) {
      <div class="panel-backdrop" (click)="expanded.set(false)" aria-hidden="true"></div>
    }

    <div class="card stack panel" [class.open]="expanded()">
      <div class="panel-head">
        <h2>{{ i18n.t('presence.title') }}</h2>
        <button class="panel-close" type="button" [attr.aria-label]="i18n.t('common.close')" (click)="expanded.set(false)">✕</button>
      </div>
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
    .panel-head { display: flex; align-items: center; justify-content: space-between; }
    .panel h2 { font-size: 1rem; margin: 0 0 var(--space-2); }
    .fab, .panel-close, .panel-backdrop { display: none; }
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

    /* Mobile: the panel becomes a floating, collapsible list opened from a side button. */
    @media (max-width: 999px) {
      .fab {
        display: flex; align-items: center; justify-content: center;
        position: fixed; inset-inline-end: 14px; inset-block-end: 18px;
        inline-size: 52px; block-size: 52px; border-radius: 999px; border: none;
        background: var(--color-primary); color: #fff; font-size: 1.35rem; cursor: pointer;
        box-shadow: 0 6px 18px rgba(0, 0, 0, 0.25); z-index: 46;
      }
      .fab-badge {
        position: absolute; inset-block-start: -4px; inset-inline-start: -4px;
        background: #2e9e5b; color: #fff; border-radius: 999px;
        min-inline-size: 20px; block-size: 20px; padding: 0 5px; font-size: 0.72rem; font-weight: 700;
        display: inline-flex; align-items: center; justify-content: center;
        border: 2px solid var(--color-surface);
      }
      .panel-backdrop { display: block; position: fixed; inset: 0; background: rgba(0, 0, 0, 0.25); z-index: 45; }
      .panel { display: none; }
      .panel.open {
        display: flex; flex-direction: column;
        position: fixed; inset-inline-end: 12px; inset-block-end: 82px;
        inline-size: min(80vw, 300px); max-block-size: 65vh; overflow-y: auto;
        box-shadow: 0 10px 30px rgba(0, 0, 0, 0.25); z-index: 46;
      }
      .panel-close {
        display: block; border: none; background: none; cursor: pointer;
        font-size: 1rem; min-inline-size: 36px; min-block-size: 36px; border-radius: 8px;
      }
      .panel-close:hover { background: var(--color-bg); }
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
  /** Mobile floating list open/closed; irrelevant on desktop where the panel is always shown. */
  protected readonly expanded = signal(false);
  protected readonly onlineCount = computed(() => this.rows().filter((row) => row.status === 'online').length);

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
