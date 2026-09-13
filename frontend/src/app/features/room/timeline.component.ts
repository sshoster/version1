import { DatePipe } from '@angular/common';
import { Component, inject, input, signal } from '@angular/core';
import { I18nService } from '../../core/i18n.service';
import { TimelineEntry } from '../../core/models';
import { ProposalsService } from '../../core/proposals.service';

/** Plain-language timeline (trust-model invariant 13) — loads lazily when expanded. */
@Component({
  selector: 'app-timeline',
  imports: [DatePipe],
  template: `
    <details class="card" (toggle)="onToggle($event)">
      <summary>{{ i18n.t('tl.title') }}</summary>
      <ol class="entries">
        @for (entry of entries(); track entry.id) {
          <li>
            <span class="when muted">{{ entry.occurredAt | date: 'short' }}</span>
            <span class="what">{{ describe(entry) }}</span>
          </li>
        }
      </ol>
    </details>
  `,
  styles: `
    summary { cursor: pointer; font-weight: 600; min-height: 32px; }
    .entries { list-style: none; margin: var(--space-3) 0 0; padding: 0; display: grid; gap: var(--space-2); }
    .entries li { display: flex; gap: var(--space-3); align-items: baseline; }
    .when { font-size: 0.78rem; white-space: nowrap; }
  `,
})
export class TimelineComponent {
  protected readonly i18n = inject(I18nService);
  private readonly service = inject(ProposalsService);

  readonly roomId = input.required<string>();

  protected readonly entries = signal<TimelineEntry[]>([]);
  private loaded = false;

  protected onToggle(event: Event): void {
    if ((event.target as HTMLDetailsElement).open && !this.loaded) {
      this.refresh();
    }
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
    const version = entry.metadata['version'] ?? '';
    const key = `tl.${entry.action}`;
    const text = this.i18n.t(key, actor, version);
    // Unknown/new actions fall back to a readable generic line.
    return text === key ? `${actor} · ${entry.action}` : text;
  }
}
