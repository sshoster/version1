import { Component, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { I18nService } from '../../core/i18n.service';
import { MessagingService, ShareRequestBody } from '../../core/messaging.service';
import {
  ContentOrigin,
  ParticipantResponse,
  SharePreviewResult,
  VisibilityScope,
} from '../../core/models';

export interface ShareIntent {
  text: string;
  sourceDraftId?: string;
  draftText?: string;
}

/**
 * The critical sharing flow (design doc §11.4): edit → choose who sees it → EXACT preview →
 * deliberate confirmation. Any change after the preview forces a new preview (server-enforced).
 */
@Component({
  selector: 'app-share-dialog',
  imports: [FormsModule],
  template: `
    <div class="overlay" role="dialog" aria-modal="true" [attr.aria-label]="i18n.t('share.title')">
      <div class="card dialog stack">
        @if (step() === 'compose') {
          <h2>{{ i18n.t('share.title') }}</h2>
          @if (error()) {
            <div class="error-box" role="alert">{{ error() }}</div>
          }
          <div class="field">
            <textarea rows="5" name="shareText" [(ngModel)]="text" (ngModelChange)="error.set(null)"></textarea>
            <span class="hint">{{ originLabel() }}</span>
          </div>
          <div class="field">
            <label for="scope">{{ i18n.t('share.whoSees') }}</label>
            <select id="scope" name="scope" [(ngModel)]="scope">
              <option value="ALL_PARTIES">{{ i18n.t('scope.ALL_PARTIES') }}</option>
              <option value="ALL_ROOM_PARTICIPANTS">{{ i18n.t('scope.ALL_ROOM_PARTICIPANTS') }}</option>
              <option value="MY_ADVISORS">{{ i18n.t('scope.MY_ADVISORS') }}</option>
              <option value="SELECTED_PARTICIPANTS">{{ i18n.t('scope.SELECTED_PARTICIPANTS') }}</option>
            </select>
          </div>
          @if (scope === 'SELECTED_PARTICIPANTS') {
            <fieldset class="recipients">
              <legend>{{ i18n.t('share.chooseRecipients') }}</legend>
              @for (participant of selectableParticipants(); track participant.id) {
                <label class="recipient-option">
                  <input
                    type="checkbox"
                    [checked]="selectedIds().has(participant.id)"
                    (change)="toggleRecipient(participant.id)"
                  />
                  {{ participant.displayName }}
                </label>
              }
            </fieldset>
          }
          <div class="actions">
            <button class="btn btn-quiet" type="button" (click)="closed.emit()">{{ i18n.t('common.cancel') }}</button>
            <button class="btn btn-primary" type="button" [disabled]="busy() || !text.trim()" (click)="toPreview()">
              {{ i18n.t('share.toPreview') }}
            </button>
          </div>
        } @else {
          <h2>{{ i18n.t('share.previewTitle') }}</h2>
          <p class="muted">{{ i18n.t('share.previewNote') }}</p>
          <blockquote class="preview-text">{{ preview()!.text }}</blockquote>
          <p class="hint">{{ previewOriginLabel() }}</p>
          <div>
            <strong>{{ i18n.t('share.previewRecipients') }}</strong>
            <ul class="recipient-list">
              @for (recipient of preview()!.recipients; track recipient.participantId) {
                <li>{{ recipient.displayName }}</li>
              }
            </ul>
          </div>
          @if (error()) {
            <div class="error-box" role="alert">{{ error() }}</div>
          }
          <div class="actions">
            <button class="btn btn-secondary" type="button" [disabled]="busy()" (click)="step.set('compose')">
              {{ i18n.t('share.back') }}
            </button>
            <button class="btn btn-primary" type="button" [disabled]="busy()" (click)="confirm()">
              {{ i18n.t('share.confirm') }}
            </button>
          </div>
        }
      </div>
    </div>
  `,
  styles: `
    .overlay {
      position: fixed; inset: 0; background: rgba(31, 41, 51, 0.45);
      display: flex; align-items: flex-end; justify-content: center; z-index: 50; padding: var(--space-3);
    }
    @media (min-width: 720px) { .overlay { align-items: center; } }
    .dialog { width: 100%; max-width: 560px; max-height: 90vh; overflow-y: auto; }
    .actions { display: flex; justify-content: space-between; gap: var(--space-2); }
    .preview-text {
      margin: 0; padding: var(--space-3); background: var(--color-primary-soft);
      border-radius: var(--radius); white-space: pre-wrap;
    }
    .recipients { border: 1px solid var(--color-border); border-radius: var(--radius); padding: var(--space-2) var(--space-3); }
    .recipient-option { display: flex; gap: var(--space-2); align-items: center; min-height: 40px; }
    .recipient-list { margin: var(--space-1) 0 0; padding-inline-start: var(--space-4); }
  `,
})
export class ShareDialogComponent {
  protected readonly i18n = inject(I18nService);
  private readonly messaging = inject(MessagingService);

  readonly roomId = input.required<string>();
  readonly intent = input.required<ShareIntent>();
  readonly participants = input.required<ParticipantResponse[]>();
  readonly myDisplayName = input.required<string>();

  readonly closed = output<void>();
  readonly published = output<void>();

  protected readonly step = signal<'compose' | 'preview'>('compose');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly preview = signal<SharePreviewResult | null>(null);
  protected readonly selectedIds = signal<Set<string>>(new Set());

  protected text = '';
  protected scope: VisibilityScope = 'ALL_PARTIES';

  ngOnInit(): void {
    this.text = this.intent().text;
  }

  protected selectableParticipants = computed(() => this.participants());

  protected toggleRecipient(id: string): void {
    this.selectedIds.update((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  /** Origin is derived, not chosen: exact draft text = accepted; changed draft = edited. */
  private origin(): ContentOrigin {
    const intent = this.intent();
    if (!intent.sourceDraftId) return 'USER_AUTHORED';
    return this.text.trim() === (intent.draftText ?? '').trim() ? 'AI_DRAFT_ACCEPTED' : 'AI_DRAFT_USER_EDITED';
  }

  protected originLabel(): string {
    return this.i18n.t(`origin.${this.origin()}`, this.myDisplayName());
  }

  protected previewOriginLabel(): string {
    const previewed = this.preview();
    return previewed ? this.i18n.t(`origin.${previewed.origin}`, this.myDisplayName()) : '';
  }

  private body(): ShareRequestBody {
    const origin = this.origin();
    return {
      text: this.text.trim(),
      scope: this.scope,
      recipientParticipantIds: this.scope === 'SELECTED_PARTICIPANTS' ? [...this.selectedIds()] : null,
      origin,
      sourceDraftId: origin === 'USER_AUTHORED' ? null : this.intent().sourceDraftId,
    };
  }

  protected toPreview(): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    this.messaging.sharePreview(this.roomId(), this.body()).subscribe({
      next: (result) => {
        this.preview.set(result);
        this.step.set('preview');
        this.busy.set(false);
      },
      error: (err: { error?: { message?: string } }) => {
        this.busy.set(false);
        this.error.set(err?.error?.message ?? this.i18n.t('auth.genericError'));
      },
    });
  }

  protected confirm(): void {
    const previewed = this.preview();
    if (this.busy() || !previewed) return;
    this.busy.set(true);
    this.error.set(null);
    this.messaging.publish(this.roomId(), previewed.previewId, this.body()).subscribe({
      next: () => {
        this.busy.set(false);
        this.published.emit();
      },
      error: (err: { status?: number; error?: { message?: string } }) => {
        this.busy.set(false);
        if (err?.status === 409) {
          // Drift or expiry: the server invalidated the preview — back to editing, fresh preview.
          this.step.set('compose');
          this.preview.set(null);
          this.error.set(this.i18n.t('share.conflict'));
        } else {
          this.error.set(err?.error?.message ?? this.i18n.t('auth.genericError'));
        }
      },
    });
  }
}
