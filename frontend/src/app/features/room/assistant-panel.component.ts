import { DatePipe } from '@angular/common';
import { Component, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { I18nService } from '../../core/i18n.service';
import { MessagingService } from '../../core/messaging.service';
import { PrivateMessage } from '../../core/models';
import { ShareIntent } from './share-dialog.component';

/** The private space: user ↔ assistant. Nothing here is visible to anyone else. */
@Component({
  selector: 'app-assistant-panel',
  imports: [FormsModule, DatePipe],
  template: `
    <p class="trust-line badge badge-private">🔒 {{ i18n.t('trust.private') }}</p>

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
              <button class="btn btn-quiet" type="button" (click)="share.emit({ text: message.text })">
                {{ i18n.t('assistant.shareOwn') }}
              </button>
            } @else {
              <button
                class="btn btn-secondary"
                type="button"
                (click)="share.emit({ text: message.text, sourceDraftId: message.id, draftText: message.text })"
              >
                {{ i18n.t('assistant.share') }}
              </button>
            }
          </div>
        </div>
      }
    </div>

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
    .composer textarea { flex: 1; }
  `,
})
export class AssistantPanelComponent {
  protected readonly i18n = inject(I18nService);
  private readonly messaging = inject(MessagingService);

  readonly roomId = input.required<string>();
  readonly share = output<ShareIntent>();

  protected readonly messages = signal<PrivateMessage[]>([]);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected draft = '';

  ngOnInit(): void {
    this.load();
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

  protected send(): void {
    const text = this.draft.trim();
    if (!text || this.busy()) return;
    this.busy.set(true);
    this.messaging.writePrivateMessage(this.roomId(), text).subscribe({
      next: (message) => {
        this.messages.update((current) => [...current, message]);
        this.draft = '';
        this.busy.set(false);
      },
      error: () => this.busy.set(false),
    });
  }

  protected requestDraft(message: PrivateMessage): void {
    this.busy.set(true);
    this.messaging.requestAiDraft(this.roomId(), message.id).subscribe({
      next: (draft) => {
        this.messages.update((current) => [...current, draft]);
        this.busy.set(false);
      },
      error: () => this.busy.set(false),
    });
  }
}
