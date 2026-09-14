import { Component, DestroyRef, computed, effect, inject, input, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { OutcomesService } from '../../core/outcomes.service';
import { I18nService } from '../../core/i18n.service';
import { ParticipantResponse, RoomResponse } from '../../core/models';
import { NotificationsService } from '../../core/notifications.service';
import { ProposalContent } from '../../core/proposals.service';
import { RoomEventsService } from '../../core/room-events.service';
import { RoomsService } from '../../core/rooms.service';
import { AssistantPanelComponent } from './assistant-panel.component';
import { FilesPanelComponent } from './files-panel.component';
import { InviteCardComponent } from './invite-card.component';
import { JoinRequestsCardComponent } from './join-requests-card.component';
import { NegotiationPanelComponent } from './negotiation-panel.component';
import { ParticipantsPanelComponent } from './participants-panel.component';
import { ProposalsPanelComponent } from './proposals-panel.component';
import { ShareDialogComponent, ShareIntent } from './share-dialog.component';
import { SharedPanelComponent } from './shared-panel.component';
import { TimelineComponent } from './timeline.component';

/**
 * The one-screen Discussion surface (design doc §11.3). Desktop layout (RTL): a static right
 * column (add people + files), the scrolling conversation in the middle, and a static left column
 * (participants with presence). Everything stacks on mobile.
 */
@Component({
  selector: 'app-room-page',
  imports: [
    RouterLink,
    AssistantPanelComponent, SharedPanelComponent, ShareDialogComponent,
    NegotiationPanelComponent, ParticipantsPanelComponent, ProposalsPanelComponent, TimelineComponent,
    FilesPanelComponent, InviteCardComponent, JoinRequestsCardComponent,
  ],
  template: `
    <div class="page room-page">
      @if (loading()) {
        <p class="muted">{{ i18n.t('common.loading') }}</p>
      } @else if (room(); as r) {
        <div class="room-layout">
          <!-- Static start-side column (right in RTL): add people + files. -->
          <aside class="side-column start-col">
            @if (isOwner()) {
              <app-join-requests-card [roomId]="roomId()" />
              <app-invite-card [roomId]="roomId()" (invited)="onInvited()" />
            }
            <app-files-panel
              [roomId]="roomId()"
              [canUpload]="isParty() || isAdvisor()"
              [participants]="participants()"
            />
          </aside>

          <div class="stack main-column">
            <header class="card head">
              <div class="head-row">
                <h1>{{ r.title }}</h1>
                @if (isParty() || isAdvisor()) {
                  <a class="btn" [class.btn-primary]="r.status === 'AGREED'" [class.btn-secondary]="r.status !== 'AGREED'"
                     [routerLink]="['/rooms', roomId(), 'result']">
                    {{ i18n.t('outcomes.link') }}
                  </a>
                }
              </div>
              <p class="status">{{ i18n.t('status.' + r.status) }}</p>
              @if (r.joinCode) {
                <button class="code-chip" type="button" (click)="copyCode(r.joinCode!)" [attr.aria-label]="i18n.t('join.codeLabel')">
                  {{ codeCopied() ? i18n.t('join.codeCopied') : i18n.t('join.codeLabel') + ': ' + r.joinCode + ' ⧉' }}
                </button>
              }
              @if (r.objective) {
                <p class="muted objective">{{ r.objective }}</p>
              }
            </header>

            @if (isParty()) {
              <app-negotiation-panel
                [roomId]="roomId()" [roomStatus]="r.status"
                (createProposal)="createProposal($event)"
              />
            }
            <app-proposals-panel [roomId]="roomId()" [isParty]="isParty()" />

            @if (isParty()) {
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

            @if (isParty()) {
              <div class="lifecycle no-print">
                @if (r.status === 'PAUSED') {
                  <button class="btn btn-secondary" type="button" (click)="lifecycle('resume')">{{ i18n.t('room.lifecycle.resume') }}</button>
                } @else if (r.status === 'CLOSED') {
                  @if (isOwner()) {
                    <button class="btn btn-secondary" type="button" (click)="lifecycle('reopen')">{{ i18n.t('room.lifecycle.reopen') }}</button>
                  }
                } @else {
                  <button class="btn btn-quiet" type="button" (click)="lifecycle('pause')">{{ i18n.t('room.lifecycle.pause') }}</button>
                  <button class="btn btn-quiet" type="button" (click)="lifecycle('close')">{{ i18n.t('room.lifecycle.close') }}</button>
                }
              </div>
            }

            <app-timeline [roomId]="roomId()" />
          </div>

          <!-- Static end-side column (left in RTL): who is here, live. -->
          <aside class="side-column end-col">
            <app-participants-panel
              [roomId]="roomId()" [isOwner]="isOwner()"
              [creatorUserId]="r.ownerUserId" [myUserId]="auth.user()?.id ?? ''"
            />
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
    .room-page { max-width: 1240px; }
    .room-layout { display: flex; flex-direction: column; gap: var(--space-3); }
    .main-column { flex: 1; min-width: 0; order: 1; }
    .start-col { order: 2; }
    /* Mobile: participants strip on top of the page. */
    .end-col { order: 0; }
    .code-chip {
      display: inline-flex; align-items: center; margin-block-start: var(--space-1);
      border: 1px dashed var(--color-border); background: var(--color-bg); color: var(--color-text);
      border-radius: 999px; padding: 4px 14px; font: inherit; font-size: 0.85rem; cursor: pointer;
    }
    .code-chip:hover { border-color: var(--color-primary); }
    .side-column { display: flex; flex-direction: column; gap: var(--space-3); }
    @media (min-width: 1000px) {
      .room-layout { flex-direction: row; align-items: flex-start; }
      .main-column { order: 0; }
      .start-col { order: -1; width: 290px; flex-shrink: 0; }
      .end-col { order: 1; width: 250px; flex-shrink: 0; }
      .side-column { position: sticky; top: var(--space-3); max-height: calc(100vh - 24px); overflow-y: auto; }
    }
    /* The room-details card stays pinned while the conversation scrolls. */
    .head {
      position: sticky;
      inset-block-start: var(--space-2);
      z-index: 20;
      box-shadow: 0 4px 14px rgba(0, 0, 0, 0.07);
    }
    .head h1 { margin-block-end: var(--space-1); }
    .head-row { display: flex; align-items: center; justify-content: space-between; gap: var(--space-2); flex-wrap: wrap; }
    .head-row h1 { margin: 0; }
    .lifecycle { display: flex; gap: var(--space-2); flex-wrap: wrap; justify-content: center; }
    .status { margin: 0; color: var(--color-text-muted); }
    .objective { margin: var(--space-1) 0 0; font-size: 0.9rem; }
    .tabs { display: flex; gap: var(--space-2); }
    .tab {
      flex: 1; min-height: 48px; border: 1px solid var(--color-border); border-radius: var(--radius);
      background: var(--color-surface); font: inherit; cursor: pointer;
    }
    .tab.active { background: var(--color-primary); color: var(--color-primary-contrast); border-color: var(--color-primary); }
  `,
})
export class RoomPage {
  protected readonly i18n = inject(I18nService);
  protected readonly auth = inject(AuthService);
  private readonly rooms = inject(RoomsService);
  private readonly notifications = inject(NotificationsService);
  private readonly outcomesService = inject(OutcomesService);
  private readonly roomEvents = inject(RoomEventsService);
  private readonly destroyRef = inject(DestroyRef);

  readonly roomId = input.required<string>();

  protected readonly loading = signal(true);
  protected readonly room = signal<RoomResponse | null>(null);
  protected readonly participants = signal<ParticipantResponse[]>([]);
  protected readonly tab = signal<'shared' | 'assistant'>('shared');
  protected readonly shareIntent = signal<ShareIntent | null>(null);

  private readonly sharedPanel = viewChild(SharedPanelComponent);
  private readonly assistantPanel = viewChild(AssistantPanelComponent);
  private readonly negotiationPanel = viewChild(NegotiationPanelComponent);
  private readonly participantsPanel = viewChild(ParticipantsPanelComponent);
  private readonly proposalsPanel = viewChild(ProposalsPanelComponent);
  private readonly filesPanel = viewChild(FilesPanelComponent);
  private readonly timeline = viewChild(TimelineComponent);
  private readonly joinRequestsCard = viewChild(JoinRequestsCardComponent);

  protected readonly codeCopied = signal(false);

  protected readonly isOwner = computed(() => this.room()?.myRoles.includes('OWNER') ?? false);
  protected readonly isAdvisor = computed(() => this.room()?.myRoles.includes('ADVISOR') ?? false);
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
            case 'PROPOSAL_CREATED':
            case 'PROPOSAL_REVISED':
            case 'APPROVAL_REQUESTED':
            case 'APPROVAL_RECORDED':
              this.proposalsPanel()?.refresh();
              this.reloadRoom(id);
              break;
            case 'FILE_SHARED':
            case 'FILE_WITHDRAWN':
              this.filesPanel()?.refresh();
              break;
            case 'JOIN_REQUESTED':
            case 'JOIN_REQUEST_DECIDED':
              this.joinRequestsCard()?.refresh();
              this.participantsPanel()?.refresh();
              break;
          }
          this.timeline()?.refreshIfOpen();
          // The user is watching this room, so whatever just happened is not "unread".
          this.notifications.markRoomRead(id).subscribe({ error: () => undefined });
        });
    });
  }

  private reloadRoom(id: string): void {
    this.rooms.get(id).subscribe({
      next: (room) => {
        this.room.set(room);
        this.loading.set(false);
        // Content has actually rendered (not just navigation) — clear the home-page badge.
        // Reruns on every live event, so activity seen while inside the room stays cleared too.
        this.notifications.markRoomRead(id).subscribe({ error: () => undefined });
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

  protected lifecycle(action: 'pause' | 'resume' | 'close' | 'reopen'): void {
    const id = this.roomId();
    const call =
      action === 'pause' ? this.outcomesService.pauseRoom(id)
        : action === 'resume' ? this.outcomesService.resumeRoom(id)
          : action === 'close' ? this.outcomesService.closeRoom(id)
            : this.outcomesService.reopenRoom(id);
    call.subscribe({ next: () => this.reloadRoom(id), error: () => this.reloadRoom(id) });
  }

  protected copyCode(code: string): void {
    void navigator.clipboard.writeText(code).then(() => {
      this.codeCopied.set(true);
      setTimeout(() => this.codeCopied.set(false), 2000);
    });
  }

  protected onInvited(): void {
    this.reloadRoom(this.roomId());
    this.participantsPanel()?.refresh();
  }

  protected openShare(intent: ShareIntent): void {
    this.shareIntent.set(intent);
  }

  protected createProposal(content: ProposalContent): void {
    this.proposalsPanel()?.createFrom(content);
  }

  protected onPublished(): void {
    this.shareIntent.set(null);
    this.tab.set('shared');
    this.sharedPanel()?.load();
  }

  /** "Help me phrase it": save privately, get a suggestion, land in the assistant tab. */
  protected helpMePhrase(text: string): void {
    this.tab.set('assistant');
    setTimeout(() => this.assistantPanel()?.writeAndSuggest(text));
  }
}
