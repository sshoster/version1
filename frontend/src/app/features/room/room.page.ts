import { Component, DestroyRef, computed, effect, inject, input, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AuthService } from '../../core/auth.service';
import { I18nService } from '../../core/i18n.service';
import { ParticipantResponse, RoomResponse } from '../../core/models';
import { ProposalContent } from '../../core/proposals.service';
import { RoomEventsService } from '../../core/room-events.service';
import { RoomsService } from '../../core/rooms.service';
import { AssistantPanelComponent } from './assistant-panel.component';
import { FilesPanelComponent } from './files-panel.component';
import { InviteCardComponent } from './invite-card.component';
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
    AssistantPanelComponent, SharedPanelComponent, ShareDialogComponent,
    NegotiationPanelComponent, ParticipantsPanelComponent, ProposalsPanelComponent, TimelineComponent,
    FilesPanelComponent, InviteCardComponent,
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
              <h1>{{ r.title }}</h1>
              <p class="status">{{ i18n.t('status.' + r.status) }}</p>
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

            <app-timeline [roomId]="roomId()" />
          </div>

          <!-- Static end-side column (left in RTL): who is here, live. -->
          <aside class="side-column end-col">
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
    .room-page { max-width: 1240px; }
    .room-layout { display: flex; flex-direction: column; gap: var(--space-3); }
    .main-column { flex: 1; min-width: 0; order: 1; }
    .start-col { order: 2; }
    .end-col { order: 3; }
    .side-column { display: flex; flex-direction: column; gap: var(--space-3); }
    @media (min-width: 1000px) {
      .room-layout { flex-direction: row; align-items: flex-start; }
      .main-column { order: 0; }
      .start-col { order: -1; width: 290px; flex-shrink: 0; }
      .end-col { order: 1; width: 250px; flex-shrink: 0; }
      .side-column { position: sticky; top: var(--space-3); max-height: calc(100vh - 24px); overflow-y: auto; }
    }
    .head h1 { margin-block-end: var(--space-1); }
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
          }
          this.timeline()?.refreshIfOpen();
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
