import { DatePipe } from '@angular/common';
import { Component, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { I18nService } from '../../core/i18n.service';
import { MessagingService } from '../../core/messaging.service';
import { RoomStatus, SharedItem } from '../../core/models';
import { ShareIntent } from './share-dialog.component';

/** The shared conversation: every item carries author, provenance label, and status. */
@Component({
  selector: 'app-shared-panel',
  imports: [FormsModule, DatePipe],
  template: `
    <p class="trust-line badge">👥 {{ i18n.t('trust.shared') }}</p>

    @if (items().length === 0 && !loading()) {
      <p class="muted empty">{{ i18n.t('shared.empty') }}</p>
    }

    <div class="items">
      @for (item of items(); track item.id) {
        <article class="item" [class.mine]="item.mine" [class.withdrawn]="item.status === 'WITHDRAWN'">
          <span class="who muted">
            {{ originLabel(item) }} · {{ item.versionCreatedAt | date: 'short' }}
            @if (item.version > 1) {
              · {{ i18n.t('shared.updated', item.version) }}
            }
          </span>
          @if (item.status === 'WITHDRAWN') {
            <p class="text muted"><em>{{ i18n.t('shared.withdrawn') }}</em></p>
          } @else {
            <p class="text">{{ item.text }}</p>
          }
          <span class="scope muted">{{ i18n.t('scope.' + item.scope) }}</span>
          @if (item.mine && item.status === 'ACTIVE') {
            <button class="btn btn-quiet" type="button" (click)="withdraw(item)">
              {{ i18n.t('shared.withdraw') }}
            </button>
          }
        </article>
      }
    </div>

    @if (shareable()) {
      <form class="composer" (ngSubmit)="shareAsWritten()">
        <textarea
          rows="2"
          name="composerText"
          [placeholder]="i18n.t('shared.composerPlaceholder')"
          [(ngModel)]="draft"
        ></textarea>
        <div class="composer-actions">
          <button class="btn btn-primary" type="submit" [disabled]="!draft.trim()">
            {{ i18n.t('shared.writeMyself') }}
          </button>
          <button class="btn btn-secondary" type="button" [disabled]="!draft.trim()" (click)="askForHelp()">
            {{ i18n.t('shared.helpPhrase') }}
          </button>
        </div>
      </form>
    } @else {
      <p class="muted not-shareable">
        {{ waitingForOther() ? i18n.t('shared.waitingForOther') : i18n.t('shared.notShareable') }}
      </p>
    }
  `,
  styles: `
    .trust-line { display: inline-block; margin-block-end: var(--space-3); }
    .empty { text-align: center; padding: var(--space-4); }
    .items { display: flex; flex-direction: column; gap: var(--space-3); margin-block-end: var(--space-3); }
    .item {
      background: var(--color-surface); border: 1px solid var(--color-border);
      border-radius: var(--radius); padding: var(--space-3);
    }
    .item.mine { border-inline-start: 4px solid var(--color-primary); }
    .item.withdrawn { opacity: 0.75; }
    .who, .scope { font-size: 0.8rem; display: block; }
    .text { margin: var(--space-1) 0; white-space: pre-wrap; }
    .composer { display: flex; flex-direction: column; gap: var(--space-2); }
    .composer-actions { display: flex; gap: var(--space-2); flex-wrap: wrap; }
    .not-shareable { text-align: center; padding: var(--space-3); background: var(--color-bg); border-radius: var(--radius); }
  `,
})
export class SharedPanelComponent {
  protected readonly i18n = inject(I18nService);
  private readonly messaging = inject(MessagingService);

  readonly roomId = input.required<string>();
  readonly roomStatus = input.required<RoomStatus>();
  readonly share = output<ShareIntent>();
  readonly helpPhrase = output<string>();

  protected readonly shareable = computed(() =>
    ['INTAKE', 'ACTIVE', 'WAITING_FOR_USER'].includes(this.roomStatus()),
  );
  protected readonly waitingForOther = computed(() =>
    ['DRAFT', 'INVITING'].includes(this.roomStatus()),
  );

  protected readonly items = signal<SharedItem[]>([]);
  protected readonly loading = signal(true);
  protected draft = '';

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.messaging.sharedItems(this.roomId()).subscribe({
      next: (items) => {
        this.items.set(items);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  protected originLabel(item: SharedItem): string {
    return this.i18n.t(`origin.${item.origin}`, item.authorDisplayName);
  }

  protected shareAsWritten(): void {
    const text = this.draft.trim();
    if (!text) return;
    this.share.emit({ text });
    this.draft = '';
  }

  protected askForHelp(): void {
    const text = this.draft.trim();
    if (!text) return;
    this.helpPhrase.emit(text);
    this.draft = '';
  }

  protected withdraw(item: SharedItem): void {
    this.messaging.withdraw(this.roomId(), item.id).subscribe({
      next: () => this.load(),
    });
  }
}
