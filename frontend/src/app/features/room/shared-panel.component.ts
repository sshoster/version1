import { DatePipe } from '@angular/common';
import { Component, ElementRef, computed, inject, input, output, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { I18nService } from '../../core/i18n.service';
import { MessagingService } from '../../core/messaging.service';
import { RoomStatus, SharedItem } from '../../core/models';
import { ShareIntent } from './share-dialog.component';

/** Stable per-author accent palette (chosen by userId hash) so people are tellable apart. */
const AUTHOR_COLORS = ['#2f6ab3', '#b3612f', '#7a3fb3', '#b32f6a', '#2f9ab3', '#8a6410', '#4a7d2f', '#b3372f'];

/**
 * The shared conversation as a chat window: messages scroll inside their own pane (the page
 * stays put), my bubbles on the end side in green, others on the start side with a per-author
 * accent. Sticks to the newest message unless the reader scrolled up — then a jump chip appears.
 * Every item still carries author, provenance label, scope, and status.
 */
@Component({
  selector: 'app-shared-panel',
  imports: [FormsModule, DatePipe],
  template: `
    <p class="trust-line badge">👥 {{ i18n.t('trust.shared') }}</p>

    <div class="chat-area">
      <div class="chat-window" #scroller (scroll)="onScroll()">
        @if (items().length === 0 && !loading()) {
          <p class="muted empty">{{ i18n.t('shared.empty') }}</p>
        }
        @for (item of items(); track item.id) {
          <article class="item" [class.mine]="item.mine" [class.withdrawn]="item.status === 'WITHDRAWN'"
                   [style.--author-accent]="item.mine ? null : accent(item.authorUserId)">
            <span class="who">
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
            <span class="meta-row">
              <span class="scope muted">{{ i18n.t('scope.' + item.scope) }}</span>
              @if (item.mine && item.status === 'ACTIVE') {
                <button class="withdraw-btn" type="button" (click)="withdraw(item)">
                  {{ i18n.t('shared.withdraw') }}
                </button>
              }
            </span>
          </article>
        }
      </div>

      @if (showJump()) {
        <button class="jump" type="button" (click)="scrollToBottom(true)">
          ⬇ {{ i18n.t('shared.jumpToLatest') }}
        </button>
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
    .trust-line { display: inline-block; margin-block-end: var(--space-2); }
    .empty { text-align: center; padding: var(--space-4); }

    /* The conversation scrolls inside its own pane; the page (and composer) stay put. */
    .chat-area { position: relative; }
    .chat-window {
      display: flex; flex-direction: column; gap: var(--space-2);
      /* Grows with the conversation; once it would overflow the screen, it scrolls internally
         instead — so a fresh discussion keeps the composer right under the first messages. */
      max-block-size: clamp(300px, calc(100dvh - 330px), 900px);
      overflow-y: auto; overscroll-behavior: contain;
      padding: var(--space-1); margin-block-end: var(--space-2);
    }
    @media (max-width: 999px) {
      .chat-window { max-block-size: clamp(260px, calc(100dvh - 300px), 700px); }
    }

    .item {
      max-inline-size: 88%; align-self: flex-start;
      background: var(--color-surface); border: 1px solid var(--color-border);
      border-radius: 14px; border-start-start-radius: 4px;
      border-inline-start: 4px solid var(--author-accent, var(--color-border));
      padding: var(--space-2) var(--space-3);
    }
    .item.mine {
      align-self: flex-end;
      background: var(--color-primary-soft, #e3efe9);
      border-color: color-mix(in srgb, var(--color-primary) 30%, transparent);
      border-radius: 14px; border-start-end-radius: 4px;
      border-inline-start-width: 1px;
    }
    .item.withdrawn { opacity: 0.75; }
    .who { font-size: 0.76rem; display: block; font-weight: 600; color: var(--author-accent, var(--color-primary)); }
    .text { margin: var(--space-1) 0; white-space: pre-wrap; overflow-wrap: anywhere; }
    .meta-row { display: flex; align-items: center; justify-content: space-between; gap: var(--space-2); }
    .scope { font-size: 0.72rem; }
    .withdraw-btn {
      border: none; background: none; cursor: pointer; padding: 2px 6px;
      font-size: 0.72rem; color: var(--color-text-muted); text-decoration: underline; border-radius: 6px;
    }
    .withdraw-btn:hover { color: var(--color-danger); }

    .jump {
      position: absolute; inset-block-end: var(--space-3); inset-inline-start: 50%;
      transform: translateX(-50%);
      border: none; border-radius: 999px; cursor: pointer;
      background: var(--color-primary); color: #fff; font-weight: 600; font-size: 0.85rem;
      padding: 8px 16px; box-shadow: 0 4px 14px rgba(0, 0, 0, 0.25);
    }

    /* Floats at the bottom of the screen while the chat is in view (page-scrolling within the
       conversation keeps it reachable); scrolled past the chat card, it locks into place. */
    .composer {
      display: flex; flex-direction: column; gap: var(--space-2);
      position: sticky; inset-block-end: 0; z-index: 5;
      background: var(--color-surface);
      padding-block: var(--space-2);
      border-block-start: 1px solid var(--color-border);
    }
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

  private readonly scroller = viewChild<ElementRef<HTMLElement>>('scroller');

  protected readonly shareable = computed(() =>
    ['INTAKE', 'ACTIVE', 'WAITING_FOR_USER'].includes(this.roomStatus()),
  );
  protected readonly waitingForOther = computed(() =>
    ['DRAFT', 'INVITING'].includes(this.roomStatus()),
  );

  protected readonly items = signal<SharedItem[]>([]);
  protected readonly loading = signal(true);
  protected readonly showJump = signal(false);
  protected draft = '';

  /** Follow the newest message — until the reader deliberately scrolls up. */
  private stickToBottom = true;
  private lastMessageId: string | null = null;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.messaging.sharedItems(this.roomId()).subscribe({
      next: (items) => {
        this.items.set(items);
        this.loading.set(false);
        // Reloads happen on every live room event; only move the scroll when a message
        // actually arrived, and never with an animation that could fight a finger mid-scroll.
        const newest = items.length ? items[items.length - 1].id : null;
        const arrived = newest !== this.lastMessageId;
        this.lastMessageId = newest;
        if (arrived && this.stickToBottom) setTimeout(() => this.scrollToBottom(), 0);
      },
      error: () => this.loading.set(false),
    });
  }

  protected onScroll(): void {
    const el = this.scroller()?.nativeElement;
    if (!el) return;
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 48;
    this.stickToBottom = nearBottom;
    this.showJump.set(!nearBottom);
  }

  protected scrollToBottom(force = false): void {
    const el = this.scroller()?.nativeElement;
    if (!el) return;
    if (force) {
      // The deliberate "jump to latest" click gets the pleasant animation…
      this.stickToBottom = true;
      el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
    } else {
      // …auto-follow snaps instantly so it never competes with the user's own scrolling.
      el.scrollTop = el.scrollHeight;
    }
    this.showJump.set(false);
  }

  protected accent(authorUserId: string): string {
    let hash = 0;
    for (let i = 0; i < authorUserId.length; i++) {
      hash = (hash * 31 + authorUserId.charCodeAt(i)) | 0;
    }
    return AUTHOR_COLORS[Math.abs(hash) % AUTHOR_COLORS.length];
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
