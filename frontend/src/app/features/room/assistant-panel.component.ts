import { DatePipe } from '@angular/common';
import { Component, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { I18nService } from '../../core/i18n.service';
import { MessagingService } from '../../core/messaging.service';
import { NegotiationService } from '../../core/negotiation.service';
import { PrivateMessage, RoomStatus } from '../../core/models';
import { ShareIntent } from './share-dialog.component';

/** The private space: user ↔ assistant. Nothing here is visible to anyone else. */
@Component({
  selector: 'app-assistant-panel',
  imports: [FormsModule, DatePipe],
  template: `
    <p class="trust-line badge badge-private">🔒 {{ i18n.t('trust.private') }}</p>

    <details class="profile">
      <summary>{{ i18n.t('profile.title') }}</summary>
      <div class="stack" style="margin-block-start: var(--space-2)">
        <div class="field">
          <label for="goals">{{ i18n.t('profile.goals') }}</label>
          <textarea id="goals" name="goals" rows="2" [(ngModel)]="profileGoals"></textarea>
        </div>
        <div class="field">
          <label for="boundaries">{{ i18n.t('profile.boundaries') }}</label>
          <textarea id="boundaries" name="boundaries" rows="2" [(ngModel)]="profileBoundaries"></textarea>
        </div>
        <div class="field">
          <label for="flexibility">{{ i18n.t('profile.flexibility') }}</label>
          <textarea id="flexibility" name="flexibility" rows="2" [(ngModel)]="profileFlexibility"></textarea>
        </div>
        <button class="btn btn-secondary" type="button" [disabled]="busy()" (click)="saveProfile()">
          {{ profileSaved() ? i18n.t('profile.saved') : i18n.t('profile.save') }}
        </button>
      </div>
    </details>

    @if (messages().length === 0 && !loading()) {
      <p class="muted empty">{{ i18n.t('assistant.empty') }}</p>
    }

    <div class="messages">
      @for (message of messages(); track message.id) {
        <div class="message" [class.assistant]="message.sender === 'ASSISTANT'">
          <span class="who muted">
            {{ message.sender === 'ASSISTANT' ? i18n.t('assistant.draftLabel') : i18n.t('assistant.you') }}
            · {{ message.createdAt | date: 'short' }}
          </span>
          <p class="text">{{ message.text }}</p>
          <div class="message-actions">
            @if (message.sender === 'USER') {
              <button class="btn btn-quiet" type="button" [disabled]="busy()" (click)="requestDraft(message)">
                {{ i18n.t('assistant.suggest') }}
              </button>
              @if (shareable()) {
                <button class="btn btn-quiet" type="button" (click)="share.emit({ text: message.text })">
                  {{ i18n.t('assistant.shareOwn') }}
                </button>
              }
            } @else {
              @if (shareable()) {
                <button
                  class="btn btn-secondary"
                  type="button"
                  (click)="share.emit({ text: message.text, sourceDraftId: message.id, draftText: message.text })"
                >
                  {{ i18n.t('assistant.share') }}
                </button>
              } @else {
                <span class="hint">{{ i18n.t('shared.waitingForOther') }}</span>
              }
            }
          </div>
        </div>
      }
    </div>

    @if (thinking()) {
      <p class="thinking">🤖 {{ i18n.t('assistant.thinking') }}</p>
    }
    @if (error()) {
      <div class="error-box" role="alert">{{ error() }}</div>
    }

    <form class="composer" (ngSubmit)="send()">
      <textarea
        rows="2"
        name="privateText"
        [placeholder]="i18n.t('assistant.placeholder')"
        [(ngModel)]="draft"
      ></textarea>
      <button class="btn btn-primary" type="submit" [disabled]="busy() || !draft.trim()">
        {{ i18n.t('assistant.send') }}
      </button>
    </form>
  `,
  styles: `
    .trust-line { display: inline-block; margin-block-end: var(--space-3); }
    .profile {
      background: var(--color-bg); border-radius: var(--radius);
      padding: var(--space-2) var(--space-3); margin-block-end: var(--space-3);
    }
    .profile summary { cursor: pointer; font-weight: 600; min-height: 32px; }
    .empty { text-align: center; padding: var(--space-4); }
    .messages { display: flex; flex-direction: column; gap: var(--space-3); margin-block-end: var(--space-3); }
    .message {
      background: var(--color-surface); border: 1px solid var(--color-border);
      border-radius: var(--radius); padding: var(--space-3);
    }
    .message.assistant { background: var(--color-private-soft); border-color: var(--color-private); }
    .who { font-size: 0.8rem; }
    .text { margin: var(--space-1) 0; white-space: pre-wrap; }
    .message-actions { display: flex; gap: var(--space-2); flex-wrap: wrap; }
    .composer { display: flex; gap: var(--space-2); align-items: flex-end; }
    .thinking { color: var(--color-private); font-weight: 600; }
    .composer textarea { flex: 1; }
  `,
})
export class AssistantPanelComponent {
  protected readonly i18n = inject(I18nService);
  private readonly messaging = inject(MessagingService);

  readonly roomId = input.required<string>();
  readonly roomStatus = input.required<RoomStatus>();
  readonly share = output<ShareIntent>();

  protected readonly shareable = computed(() =>
    ['INTAKE', 'ACTIVE', 'WAITING_FOR_USER'].includes(this.roomStatus()),
  );

  private readonly negotiation = inject(NegotiationService);

  protected readonly messages = signal<PrivateMessage[]>([]);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly thinking = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly profileSaved = signal(false);
  protected draft = '';
  protected profileGoals = '';
  protected profileBoundaries = '';
  protected profileFlexibility = '';

  ngOnInit(): void {
    this.load();
    this.negotiation.profile(this.roomId()).subscribe({
      next: (profile) => {
        this.profileGoals = profile.goals;
        this.profileBoundaries = profile.boundaries;
        this.profileFlexibility = profile.flexibility;
      },
      error: () => undefined,
    });
  }

  protected saveProfile(): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.negotiation
      .saveProfile(this.roomId(), {
        goals: this.profileGoals,
        boundaries: this.profileBoundaries,
        flexibility: this.profileFlexibility,
      })
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.profileSaved.set(true);
          setTimeout(() => this.profileSaved.set(false), 2500);
        },
        error: () => this.busy.set(false),
      });
  }

  load(): void {
    this.messaging.privateMessages(this.roomId()).subscribe({
      next: (messages) => {
        this.messages.set(messages);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  /** Writes a private message and immediately asks the assistant for a suggested wording. */
  writeAndSuggest(text: string): void {
    this.busy.set(true);
    this.messaging.writePrivateMessage(this.roomId(), text).subscribe({
      next: (message) => {
        this.messages.update((current) => [...current, message]);
        this.requestDraft(message);
      },
      error: () => this.busy.set(false),
    });
  }

  /** Sending immediately asks the assistant for a suggested wording (design doc §5.2 step 2). */
  protected send(): void {
    const text = this.draft.trim();
    if (!text || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    this.messaging.writePrivateMessage(this.roomId(), text).subscribe({
      next: (message) => {
        this.messages.update((current) => [...current, message]);
        this.draft = '';
        this.busy.set(false);
        this.requestDraft(message);
      },
      error: (err: { error?: { message?: string } }) => {
        this.busy.set(false);
        this.error.set(err?.error?.message ?? this.i18n.t('assistant.error'));
      },
    });
  }

  protected requestDraft(message: PrivateMessage): void {
    this.busy.set(true);
    this.thinking.set(true);
    this.error.set(null);
    this.messaging.requestAiDraft(this.roomId(), message.id).subscribe({
      next: (draft) => {
        this.messages.update((current) => [...current, draft]);
        this.busy.set(false);
        this.thinking.set(false);
      },
      error: () => {
        this.busy.set(false);
        this.thinking.set(false);
        this.error.set(this.i18n.t('assistant.error'));
      },
    });
  }
}
