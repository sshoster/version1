import { DatePipe } from '@angular/common';
import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { I18nService } from '../../core/i18n.service';
import { OutcomeType, OutcomeView, RoomResponse } from '../../core/models';
import { OutcomesService } from '../../core/outcomes.service';
import { RoomsService } from '../../core/rooms.service';

/**
 * The Result surface (design doc §11.2 #3): the three outcome artifacts in one place, each with
 * its mandated label, regeneration, and print/PDF export.
 */
@Component({
  selector: 'app-result-page',
  imports: [RouterLink, DatePipe],
  template: `
    <div class="page stack">
      <div class="top-row">
        <h1>{{ i18n.t('outcomes.title') }}</h1>
        <a class="btn btn-quiet" [routerLink]="['/rooms', roomId()]">{{ i18n.t('outcomes.back') }}</a>
      </div>
      @if (room(); as r) {
        <p class="muted">{{ r.title }} — {{ i18n.t('status.' + r.status) }}</p>
      }
      @if (error()) {
        <div class="error-box" role="alert">{{ error() }}</div>
      }

      <!-- 1. AI discussion summary -->
      <section class="card stack">
        <div class="sec-head">
          <h2>{{ i18n.t('outcomes.summary.title') }}</h2>
          <span class="badge badge-ai">🤖 {{ i18n.t('outcomes.summary.label') }}</span>
        </div>
        @if (summary(); as artifact) {
          <p class="muted small">{{ i18n.t('outcomes.versionAt', artifact.version, '') }}{{ artifact.createdAt | date: 'short' }}</p>
          <pre class="doc-text">{{ artifact.text }}</pre>
        } @else {
          <p class="muted">{{ i18n.t('outcomes.summary.empty') }}</p>
        }
        @if (isParty()) {
          <div class="actions">
            <button class="btn btn-primary" type="button" [disabled]="busy()" (click)="generate('DISCUSSION_SUMMARY')">
              {{ summary() ? i18n.t('outcomes.regenerate') : i18n.t('outcomes.generate') }}
            </button>
            @if (summary()) {
              <button class="btn btn-secondary" type="button" (click)="print(summary()!)">{{ i18n.t('outcomes.print') }}</button>
            }
          </div>
        }
      </section>

      <!-- 2. Approved understandings -->
      <section class="card stack">
        <div class="sec-head">
          <h2>{{ i18n.t('outcomes.und.title') }}</h2>
          <span class="badge">✅ {{ i18n.t('outcomes.und.label') }}</span>
        </div>
        @if (understandings(); as artifact) {
          <p class="muted small">{{ i18n.t('outcomes.versionAt', artifact.version, '') }}{{ artifact.createdAt | date: 'short' }}</p>
          @for (block of artifact.understandings ?? []; track block.proposalId) {
            <div class="und-block">
              <strong>{{ block.title }}</strong>
              <ol>
                @for (term of block.terms; track term) {
                  <li>{{ term }}</li>
                }
              </ol>
              @if (block.assumptions.length > 0) {
                <p class="muted small">{{ i18n.t('nego.assumptions') }}: {{ block.assumptions.join(' · ') }}</p>
              }
              @for (approval of block.approvals; track approval.userId) {
                <p class="approval-line small">
                  ✔️ {{ i18n.t('outcomes.und.approvedBy', approval.displayName, format(approval.approvedAt), approval.proposalVersion) }}
                </p>
              }
            </div>
          }
        } @else {
          <p class="muted">{{ i18n.t('outcomes.und.empty') }}</p>
        }
        @if (isParty()) {
          <div class="actions">
            <button class="btn btn-primary" type="button" [disabled]="busy()" (click)="generate('APPROVED_UNDERSTANDINGS')">
              {{ understandings() ? i18n.t('outcomes.regenerate') : i18n.t('outcomes.generate') }}
            </button>
            @if (understandings()) {
              <button class="btn btn-secondary" type="button" (click)="print(understandings()!)">{{ i18n.t('outcomes.print') }}</button>
            }
          </div>
        }
      </section>

      <!-- 3. Agreement draft -->
      <section class="card stack">
        <div class="sec-head">
          <h2>{{ i18n.t('outcomes.draft.title') }}</h2>
          <span class="badge badge-warn">⚠️ {{ i18n.t('outcomes.draft.label') }}</span>
        </div>
        @if (draft(); as artifact) {
          <p class="muted small">{{ i18n.t('outcomes.versionAt', artifact.version, '') }}{{ artifact.createdAt | date: 'short' }}</p>
          <pre class="doc-text">{{ artifact.text }}</pre>
        } @else {
          <p class="muted">{{ i18n.t('outcomes.draft.empty') }}</p>
        }
        @if (isParty()) {
          <div class="actions">
            <button class="btn btn-primary" type="button" [disabled]="busy()" (click)="generate('AGREEMENT_DRAFT')">
              {{ draft() ? i18n.t('outcomes.regenerate') : i18n.t('outcomes.generate') }}
            </button>
            @if (draft()) {
              <button class="btn btn-secondary" type="button" (click)="print(draft()!)">{{ i18n.t('outcomes.print') }}</button>
            }
          </div>
        }
      </section>
    </div>
  `,
  styles: `
    .top-row { display: flex; align-items: center; justify-content: space-between; gap: var(--space-2); }
    .top-row h1 { margin: 0; }
    .sec-head { display: flex; align-items: center; gap: var(--space-2); flex-wrap: wrap; }
    .sec-head h2 { margin: 0; }
    .badge-ai { background: var(--color-private-soft); color: var(--color-private); }
    .badge-warn { background: #fdf1d7; color: #8a6410; }
    .small { font-size: 0.82rem; }
    .doc-text {
      white-space: pre-wrap; font-family: inherit; margin: 0;
      background: var(--color-bg); border-radius: var(--radius); padding: var(--space-3);
    }
    .und-block { border: 1px solid var(--color-border); border-radius: var(--radius); padding: var(--space-3); }
    .und-block ol { margin: var(--space-2) 0; padding-inline-start: var(--space-4); }
    .approval-line { margin: 2px 0; color: #1d6e3a; }
    .actions { display: flex; gap: var(--space-2); flex-wrap: wrap; }
  `,
})
export class ResultPage {
  protected readonly i18n = inject(I18nService);
  private readonly outcomes = inject(OutcomesService);
  private readonly rooms = inject(RoomsService);

  readonly roomId = input.required<string>();

  protected readonly room = signal<RoomResponse | null>(null);
  protected readonly artifacts = signal<OutcomeView[]>([]);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly summary = computed(() => this.artifacts().find((a) => a.type === 'DISCUSSION_SUMMARY') ?? null);
  protected readonly understandings = computed(() => this.artifacts().find((a) => a.type === 'APPROVED_UNDERSTANDINGS') ?? null);
  protected readonly draft = computed(() => this.artifacts().find((a) => a.type === 'AGREEMENT_DRAFT') ?? null);

  protected readonly isParty = computed(() => {
    const roles = this.room()?.myRoles ?? [];
    return roles.includes('PARTY') || roles.includes('OWNER');
  });

  constructor() {
    effect(() => {
      const id = this.roomId();
      this.rooms.get(id).subscribe({ next: (room) => this.room.set(room) });
      this.refresh(id);
    });
  }

  private refresh(id: string): void {
    this.outcomes.list(id).subscribe({
      next: (artifacts) => this.artifacts.set(artifacts),
      error: () => undefined,
    });
  }

  protected generate(type: OutcomeType): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    const call =
      type === 'DISCUSSION_SUMMARY'
        ? this.outcomes.generateSummary(this.roomId())
        : type === 'APPROVED_UNDERSTANDINGS'
          ? this.outcomes.generateUnderstandings(this.roomId())
          : this.outcomes.generateDraft(this.roomId());
    call.subscribe({
      next: () => {
        this.busy.set(false);
        this.refresh(this.roomId());
      },
      error: (err: { status?: number; error?: { message?: string } }) => {
        this.busy.set(false);
        this.error.set(err?.status === 409 ? this.i18n.t('outcomes.needAgreement') : (err?.error?.message ?? this.i18n.t('auth.genericError')));
      },
    });
  }

  protected format(iso: string): string {
    return new Date(iso).toLocaleString(this.i18n.lang() === 'he' ? 'he-IL' : 'en-US');
  }

  /** Print/PDF export: a clean standalone window with only the document (design doc §7 export). */
  protected print(artifact: OutcomeView): void {
    const titleKey =
      artifact.type === 'DISCUSSION_SUMMARY' ? 'outcomes.summary' : artifact.type === 'APPROVED_UNDERSTANDINGS' ? 'outcomes.und' : 'outcomes.draft';
    const label = this.i18n.t(`${titleKey}.label`);
    const title = this.i18n.t(`${titleKey}.title`);
    const dir = this.i18n.lang() === 'he' ? 'rtl' : 'ltr';

    let bodyHtml = '';
    if (artifact.text) {
      bodyHtml = `<pre>${escapeHtml(artifact.text)}</pre>`;
    } else if (artifact.understandings) {
      bodyHtml = artifact.understandings
        .map((block) => {
          const terms = block.terms.map((t) => `<li>${escapeHtml(t)}</li>`).join('');
          const approvals = block.approvals
            .map((a) => `<p class="ok">✔ ${escapeHtml(this.i18n.t('outcomes.und.approvedBy', a.displayName, this.format(a.approvedAt), a.proposalVersion))}</p>`)
            .join('');
          return `<h3>${escapeHtml(block.title)}</h3><ol>${terms}</ol>${approvals}`;
        })
        .join('<hr/>');
    }

    const printWindow = window.open('', '_blank', 'width=760,height=900');
    if (!printWindow) return;
    printWindow.document.write(`<!doctype html>
      <html dir="${dir}"><head><meta charset="utf-8"><title>${escapeHtml(title)}</title>
      <style>
        body { font-family: 'Segoe UI', Arial, sans-serif; margin: 40px; color: #1f2933; }
        .label { color: #6b4fa1; font-size: 13px; border: 1px solid #d9dee3; border-radius: 8px; padding: 4px 12px; display: inline-block; }
        pre { white-space: pre-wrap; font-family: inherit; font-size: 15px; line-height: 1.7; }
        .ok { color: #1d6e3a; margin: 2px 0; font-size: 13px; }
        .meta { color: #5f6b76; font-size: 12px; }
      </style></head><body>
      <h1>${escapeHtml(title)}</h1>
      <p class="label">${escapeHtml(label)}</p>
      <p class="meta">${escapeHtml(this.room()?.title ?? '')} · ${escapeHtml(this.format(artifact.createdAt))} · v${artifact.version}</p>
      ${bodyHtml}
      </body></html>`);
    printWindow.document.close();
    printWindow.focus();
    setTimeout(() => printWindow.print(), 200);
  }
}

function escapeHtml(value: string): string {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
