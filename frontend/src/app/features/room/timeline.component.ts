import { DatePipe } from '@angular/common';
import { Component, inject, input, signal } from '@angular/core';
import { I18nService } from '../../core/i18n.service';
import { TimelineEntry } from '../../core/models';
import { ProposalsService } from '../../core/proposals.service';

type Category = 'status' | 'people' | 'share' | 'file' | 'assistant' | 'approval' | 'private' | 'other';

/** Plain-language timeline (trust-model invariant 13) — colored by event category, live-updating. */
@Component({
  selector: 'app-timeline',
  imports: [DatePipe],
  template: `
    <details class="card" (toggle)="onToggle($event)">
      <summary>{{ i18n.t('tl.title') }}</summary>
      <ol class="entries">
        @for (entry of entries(); track entry.id) {
          <li [class]="'entry cat-' + category(entry)">
            <span class="icon" aria-hidden="true">{{ icon(entry) }}</span>
            <span class="body">
              <span class="what">
                {{ describe(entry) }}
                @if (decisionChip(entry); as chip) {
                  <span class="chip" [class]="'chip chip-' + entry.metadata['decision']">{{ chip }}</span>
                }
              </span>
              <span class="when muted">{{ entry.occurredAt | date: 'short' }}</span>
            </span>
          </li>
        }
      </ol>
    </details>
  `,
  styles: `
    summary { cursor: pointer; font-weight: 600; min-height: 32px; }
    .entries { list-style: none; margin: var(--space-3) 0 0; padding: 0; display: grid; gap: var(--space-2); }
    .entry {
      display: flex; gap: var(--space-2); align-items: flex-start;
      border-inline-start: 4px solid var(--color-border);
      border-radius: 6px; padding: var(--space-1) var(--space-2);
      background: var(--color-surface);
    }
    .icon { font-size: 1rem; line-height: 1.5; }
    .body { display: flex; flex-direction: column; }
    .when { font-size: 0.75rem; }
    .chip {
      display: inline-block; border-radius: 999px; padding: 0 10px; font-size: 0.78rem;
      margin-inline-start: var(--space-1); background: var(--color-bg);
    }
    .chip-APPROVED { background: #e0f2e6; color: #1d6e3a; }
    .chip-REJECTED { background: var(--color-danger-soft); color: var(--color-danger); }
    .chip-CHANGES_REQUESTED { background: #fdf1d7; color: #8a6410; }
    .cat-status { border-inline-start-color: var(--color-primary); background: var(--color-primary-soft); }
    .cat-people { border-inline-start-color: #2f6ab3; }
    .cat-share { border-inline-start-color: #2f9ab3; }
    .cat-file { border-inline-start-color: #b3612f; }
    .cat-assistant { border-inline-start-color: var(--color-private); background: var(--color-private-soft); }
    .cat-approval { border-inline-start-color: #1d6e3a; }
    .cat-private { border-inline-start-color: var(--color-border); opacity: 0.85; }
  `,
})
export class TimelineComponent {
  protected readonly i18n = inject(I18nService);
  private readonly service = inject(ProposalsService);

  readonly roomId = input.required<string>();

  protected readonly entries = signal<TimelineEntry[]>([]);
  private open = false;
  private loaded = false;

  protected onToggle(event: Event): void {
    this.open = (event.target as HTMLDetailsElement).open;
    if (this.open && !this.loaded) this.refresh();
  }

  /** Called by the room page on any live event, so an open timeline stays current. */
  refreshIfOpen(): void {
    if (this.open) this.refresh();
  }

  refresh(): void {
    this.loaded = true;
    this.service.timeline(this.roomId()).subscribe({
      next: (entries) => this.entries.set([...entries].reverse()), // newest first
      error: () => undefined,
    });
  }

  protected describe(entry: TimelineEntry): string {
    const actor = entry.actorDisplayName ?? '';
    if (entry.action === 'ROOM_STATUS_CHANGED') {
      const from = this.statusLabel(entry.metadata['from']);
      const to = this.statusLabel(entry.metadata['to']);
      return this.i18n.t('tl.ROOM_STATUS_CHANGED', from, to);
    }
    const version = entry.metadata['version'] ?? '';
    const key = `tl.${entry.action}`;
    const text = this.i18n.t(key, actor, version);
    return text === key ? `${actor} · ${entry.action}` : text;
  }

  protected decisionChip(entry: TimelineEntry): string | null {
    if (entry.action !== 'APPROVAL_RECORDED') return null;
    const decision = entry.metadata['decision'];
    return decision ? this.i18n.t(`tl.decision.${decision}`) : null;
  }

  private statusLabel(status: string | undefined): string {
    if (!status) return '';
    const key = `status.${status}`;
    const label = this.i18n.t(key);
    return label === key ? status : label;
  }

  protected category(entry: TimelineEntry): Category {
    const action = entry.action;
    if (action === 'ROOM_STATUS_CHANGED') return 'status';
    if (action.startsWith('INVITATION_') || action.startsWith('PARTICIPANT_') || action === 'ROOM_CREATED' || action === 'ROOM_UPDATED') return 'people';
    if (action.startsWith('SHARED_ITEM_')) return 'share';
    if (action.startsWith('FILE_')) return 'file';
    if (action.startsWith('NEGOTIATION_')) return 'assistant';
    if (action.startsWith('PROPOSAL_') || action.startsWith('APPROVAL_')) return 'approval';
    if (['PRIVATE_MESSAGE_WRITTEN', 'AI_DRAFT_CREATED', 'AGENT_PROFILE_UPDATED', 'QUESTION_ANSWERED'].includes(action)) return 'private';
    return 'other';
  }

  protected icon(entry: TimelineEntry): string {
    switch (this.category(entry)) {
      case 'status': return '🔄';
      case 'people': return '👤';
      case 'share': return '💬';
      case 'file': return '📎';
      case 'assistant': return '🤖';
      case 'approval': return '🤝';
      case 'private': return '🔒';
      default: return '•';
    }
  }
}
