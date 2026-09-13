import { Component, DestroyRef, computed, effect, inject, input, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth.service';
import { I18nService } from '../../core/i18n.service';
import { InvitationCreated, ParticipantResponse, ParticipantRole, RoomResponse } from '../../core/models';
import { RoomEventsService } from '../../core/room-events.service';
import { RoomsService } from '../../core/rooms.service';
import { AssistantPanelComponent } from './assistant-panel.component';
import { NegotiationPanelComponent } from './negotiation-panel.component';
import { ParticipantsPanelComponent } from './participants-panel.component';
import { ShareDialogComponent, ShareIntent } from './share-dialog.component';
import { SharedPanelComponent } from './shared-panel.component';

/**
 * The one-screen Discussion surface (design doc §11.3): shared/assistant switch, plain-language
 * status, panels, and everything secondary behind "More".
 */
@Component({
  selector: 'app-room-page',
  imports: [
    FormsModule, AssistantPanelComponent, SharedPanelComponent, ShareDialogComponent,
    NegotiationPanelComponent, ParticipantsPanelComponent,
  ],
  template: `
    <div class="page room-page">
      @if (loading()) {
        <p class="muted">{{ i18n.t('common.loading') }}</p>
      } @else if (room(); as r) {
        <div class="room-layout">
        <div class="stack main-column">
        <header class="card head">
          <h1>{{ r.title }}</h1>
          <p class="status">{{ i18n.t('status.' + r.status) }}</p>
        </header>

        @if (isParty()) {
          <app-negotiation-panel [roomId]="roomId()" [roomStatus]="r.status" />
          <div class="tabs" role="tablist">
            <button
              role="tab" class="tab" [class.active]="tab() === 'shared'"
              [attr.aria-selected]="tab() === 'shared'" (click)="tab.set('shared')"
            >
              {{ i18n.t('tab.shared') }}
            </button>
            <button
              role="tab" class="tab" [class.active]="tab() === 'assistant'"
              [attr.aria-selected]="tab() === 'assistant'" (click)="tab.set('assistant')"
            >
              {{ i18n.t('tab.assistant') }}
            </button>
          </div>
        }

        <div class="card">
          @if (tab() === 'assistant' && isParty()) {
            <app-assistant-panel [roomId]="roomId()" [roomStatus]="r.status" (share)="openShare($event)" />
          } @else {
            <app-shared-panel
              [roomId]="roomId()"
              [roomStatus]="r.status"
              (share)="openShare($event)"
              (helpPhrase)="helpMePhrase($event)"
            />
          }
        </div>

        <details class="card">
          <summary>{{ i18n.t('more.title') }}</summary>
          <div class="stack" style="margin-block-start: var(--space-3)">
            @if (r.objective) {
              <p class="muted"><strong>{{ i18n.t('room.objective') }}:</strong> {{ r.objective }}</p>
            }

            @if (isOwner()) {
              <h2>{{ i18n.t('room.invite') }}</h2>
              @if (!invitation()) {
                <div class="field">
                  <label for="role">{{ i18n.t('room.invite.role') }}</label>
                  <select id="role" name="role" [(ngModel)]="inviteRole">
                    <option value="PARTY">{{ i18n.t('room.role.PARTY') }}</option>
                    <option value="ADVISOR">{{ i18n.t('room.role.ADVISOR') }}</option>
                    <option value="OBSERVER">{{ i18n.t('room.role.OBSERVER') }}</option>
                  </select>
                </div>
                <div class="name-row">
                  <div class="field">
                    <label for="inviteFirstName">{{ i18n.t('invite.firstName') }}</label>
                    <input id="inviteFirstName" name="inviteFirstName" type="text" [(ngModel)]="inviteFirstName" />
                  </div>
                  <div class="field">
                    <label for="inviteLastName">{{ i18n.t('invite.lastName') }}</label>
                    <input id="inviteLastName" name="inviteLastName" type="text" [(ngModel)]="inviteLastName" />
                  </div>
                </div>
                <p class="hint">{{ i18n.t('invite.nameHint') }}</p>
                <div class="field">
                  <label for="inviteEmail">{{ i18n.t('room.invite.email') }}</label>
                  <input id="inviteEmail" name="inviteEmail" type="email" dir="ltr" [(ngModel)]="inviteEmail" />
                  <span class="hint">{{ i18n.t('room.invite.emailHint') }}</span>
                </div>
                <p class="muted">{{ i18n.t('room.invite.explain') }}</p>
                <button class="btn btn-primary" type="button" [disabled]="busy() || !inviteFirstName.trim()" (click)="invite()">
                  {{ i18n.t('room.invite.create') }}
                </button>
              } @else {
                @if (invitation()!.email && invitation()!.emailSent) {
                  <p><span class="badge">{{ i18n.t('room.invite.emailSentTo', invitation()!.email!) }}</span></p>
                } @else if (invitation()!.email && !invitation()!.emailSent) {
                  <div class="error-box">{{ i18n.t('room.invite.emailNotSent') }}</div>
                }
                <p class="muted">{{ i18n.t('room.invite.shareHint') }}</p>
                <div class="invite-link" dir="ltr">{{ invitation()!.acceptUrl }}</div>
                <button class="btn btn-secondary" type="button" (click)="copyLink()">
                  {{ copied() ? i18n.t('room.invite.copied') : i18n.t('room.invite.copy') }}
                </button>
              }
            }
          </div>
        </details>
        </div>

        <aside class="side-column">
          <app-participants-panel [roomId]="roomId()" [isOwner]="isOwner()" />
        </aside>
        </div>
      }
    </div>

    @if (shareIntent(); as intent) {
      <app-share-dialog
        [roomId]="roomId()"
        [intent]="intent"
        [participants]="participants()"
        [myDisplayName]="auth.user()?.displayName ?? ''"
        (closed)="shareIntent.set(null)"
        (published)="onPublished()"
      />
    }
  `,
  styles: `
    .room-page { max-width: 960px; }
    .room-layout { display: flex; flex-direction: column-reverse; gap: var(--space-3); }
    .main-column { flex: 1; min-width: 0; }
    .side-column { flex-shrink: 0; }
    @media (min-width: 900px) {
      .room-layout { flex-direction: row; align-items: flex-start; }
      .side-column { width: 250px; position: sticky; top: var(--space-3); }
    }
    .name-row { display: flex; gap: var(--space-2); }
    .name-row .field { flex: 1; }
    .head h1 { margin-block-end: var(--space-1); }
    .status { margin: 0; color: var(--color-text-muted); }
    .tabs { display: flex; gap: var(--space-2); }
    .tab {
      flex: 1; min-height: 48px; border: 1px solid var(--color-border); border-radius: var(--radius);
      background: var(--color-surface); font: inherit; cursor: pointer;
    }
    .tab.active { background: var(--color-primary); color: var(--color-primary-contrast); border-color: var(--color-primary); }
    .invite-link {
      background: var(--color-bg); border: 1px dashed var(--color-border); border-radius: var(--radius);
      padding: var(--space-2) var(--space-3); overflow-wrap: anywhere; font-size: 0.85rem;
      margin-block-end: var(--space-2);
    }
    .participants { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--space-2); }
    summary { cursor: pointer; font-weight: 600; min-height: 32px; }
  `,
})
export class RoomPage {
  protected readonly i18n = inject(I18nService);
  protected readonly auth = inject(AuthService);
  private readonly rooms = inject(RoomsService);
  private readonly roomEvents = inject(RoomEventsService);
  private readonly destroyRef = inject(DestroyRef);

  readonly roomId = input.required<string>();

  protected readonly loading = signal(true);
  protected readonly room = signal<RoomResponse | null>(null);
  protected readonly participants = signal<ParticipantResponse[]>([]);
  protected readonly invitation = signal<InvitationCreated | null>(null);
  protected readonly busy = signal(false);
  protected readonly copied = signal(false);
  protected readonly tab = signal<'shared' | 'assistant'>('shared');
  protected readonly shareIntent = signal<ShareIntent | null>(null);

  protected inviteRole: ParticipantRole = 'PARTY';
  protected inviteEmail = '';
  protected inviteFirstName = '';
  protected inviteLastName = '';

  private readonly sharedPanel = viewChild(SharedPanelComponent);
  private readonly assistantPanel = viewChild(AssistantPanelComponent);
  private readonly negotiationPanel = viewChild(NegotiationPanelComponent);
  private readonly participantsPanel = viewChild(ParticipantsPanelComponent);

  protected readonly isOwner = computed(() => this.room()?.myRoles.includes('OWNER') ?? false);
  protected readonly isParty = computed(() => {
    const roles = this.room()?.myRoles ?? [];
    return roles.includes('PARTY') || roles.includes('OWNER');
  });

  constructor() {
    effect(() => {
      const id = this.roomId();
      this.loading.set(true);
      this.reloadRoom(id);
      this.reloadParticipants(id);
      this.roomEvents
        .watchRoom(id)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe((event) => {
          switch (event.type) {
            case 'ROOM_UPDATED':
              this.reloadRoom(id);
              break;
            case 'PARTICIPANT_JOINED':
              this.reloadParticipants(id);
              this.reloadRoom(id);
              this.participantsPanel()?.refresh();
              break;
            case 'PRESENCE_CHANGED':
              this.participantsPanel()?.refresh();
              break;
            case 'SHARED_ITEM_PUBLISHED':
            case 'SHARED_ITEM_WITHDRAWN':
              this.sharedPanel()?.load();
              break;
            case 'NEGOTIATION_STARTED':
            case 'NEGOTIATION_TURN_COMPLETED':
            case 'NEGOTIATION_WAITING_FOR_USER':
            case 'QUESTION_CREATED':
              this.negotiationPanel()?.refresh();
              this.reloadRoom(id);
              break;
          }
        });
    });
  }

  private reloadRoom(id: string): void {
    this.rooms.get(id).subscribe({
      next: (room) => {
        this.room.set(room);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  private reloadParticipants(id: string): void {
    this.rooms.participants(id).subscribe({
      next: (participants) => this.participants.set(participants),
      error: () => undefined,
    });
  }

  protected openShare(intent: ShareIntent): void {
    this.shareIntent.set(intent);
  }

  protected onPublished(): void {
    this.shareIntent.set(null);
    this.tab.set('shared');
    this.sharedPanel()?.load();
  }

  /** "Help me phrase it": save privately, get a suggestion, land in the assistant tab. */
  protected helpMePhrase(text: string): void {
    this.tab.set('assistant');
    // The assistant panel mounts on tab switch; queue the request for after render.
    setTimeout(() => this.assistantPanel()?.writeAndSuggest(text));
  }

  protected invite(): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.rooms
      .invite(
        this.roomId(), this.inviteRole, this.inviteEmail.trim() || null,
        this.inviteFirstName.trim() || null, this.inviteLastName.trim() || null,
      )
      .subscribe({
        next: (created) => {
          this.invitation.set(created);
          this.busy.set(false);
          this.reloadRoom(this.roomId());
          this.participantsPanel()?.refresh();
        },
        error: () => this.busy.set(false),
      });
  }

  protected copyLink(): void {
    const url = this.invitation()?.acceptUrl;
    if (!url) return;
    void navigator.clipboard.writeText(url).then(() => {
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 2500);
    });
  }

  protected roleNames(participant: ParticipantResponse): string {
    return participant.roles.map((role) => this.i18n.t(`room.role.${role}`)).join(', ');
  }
}
