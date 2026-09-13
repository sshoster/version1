import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { I18nService } from '../../core/i18n.service';
import { NegotiationRunView, RoomStatus, TurnProposal } from '../../core/models';
import { NegotiationService } from '../../core/negotiation.service';

/**
 * The automated-round surface: start button, live progress, inline question answering, and the
 * plain-language round summary ("what happens next" — design doc §11.3).
 */
@Component({
  selector: 'app-negotiation-panel',
  imports: [FormsModule],
  template: `
    @if (visible()) {
      <div class="card stack">
        @if (run(); as r) {
          @switch (r.status) {
            @case ('RUNNING') {
              <p class="running">🤝 {{ i18n.t('nego.running', r.turnCount, r.maxTurns) }}</p>
              <button class="btn btn-quiet" type="button" (click)="pause(r.id)">{{ i18n.t('nego.pause') }}</button>
            }
            @case ('WAITING_FOR_USER') {
              @if (r.myOpenQuestions.length > 0) {
                <div class="question-box">
                  <strong>{{ i18n.t('nego.questionForYou') }}</strong>
                  @for (question of r.myOpenQuestions; track question.id) {
                    <p class="q-text">{{ question.text }}</p>
                    <div class="answer-row">
                      <input
                        type="text" name="answer-{{ question.id }}"
                        [placeholder]="i18n.t('nego.answerPlaceholder')"
                        [(ngModel)]="answers[question.id]"
                      />
                      <button
                        class="btn btn-primary" type="button" [disabled]="busy() || !answers[question.id]?.trim()"
                        (click)="answer(question.id)"
                      >
                        {{ i18n.t('nego.answerSend') }}
                      </button>
                    </div>
                  }
                </div>
              } @else {
                <p class="muted">{{ i18n.t('status.WAITING_FOR_USER') }}</p>
              }
            }
            @case ('PAUSED') {
              <p class="muted">{{ i18n.t('nego.paused') }}</p>
              <button class="btn btn-primary" type="button" [disabled]="busy()" (click)="resume(r.id)">
                {{ i18n.t('nego.resume') }}
              </button>
            }
            @case ('COMPLETED') {
              <h2>{{ i18n.t('nego.resultTitle') }}</h2>
              <p>{{ i18n.t('nego.stop.' + r.stopReason) }}</p>
              @if (r.result; as result) {
                @if (result.recommendedProposal; as proposal) {
                  <div class="proposal-card">
                    <strong>{{ i18n.t('nego.recommended') }}: {{ proposal.title }}</strong>
                    <ul>
                      @for (term of proposal.terms; track term) {
                        <li>{{ term }}</li>
                      }
                    </ul>
                  </div>
                }
                @if (result.agreedPoints.length > 0) {
                  <p><strong>{{ i18n.t('nego.agreedPoints') }}:</strong> {{ result.agreedPoints.join(' · ') }}</p>
                }
                @if (result.unresolvedPoints.length > 0) {
                  <p><strong>{{ i18n.t('nego.unresolved') }}:</strong> {{ result.unresolvedPoints.join(' · ') }}</p>
                }
                @if (result.assumptions.length > 0) {
                  <p class="muted"><strong>{{ i18n.t('nego.assumptions') }}:</strong> {{ result.assumptions.join(' · ') }}</p>
                }
              }
              @if (canStart()) {
                <button class="btn btn-secondary" type="button" [disabled]="busy()" (click)="start()">
                  {{ i18n.t('nego.start') }}
                </button>
              }
            }
            @case ('FAILED') {
              <div class="error-box">{{ i18n.t('nego.stop.' + r.stopReason) }}</div>
              @if (canStart()) {
                <button class="btn btn-primary" type="button" [disabled]="busy()" (click)="start()">
                  {{ i18n.t('nego.retry') }}
                </button>
              }
            }
          }

          @if (r.turns.length > 0) {
            <details [open]="r.status === 'RUNNING'">
              <summary>{{ i18n.t('nego.transcript') }}</summary>
              <div class="turns">
                @for (turn of r.turns; track turn.turnNumber) {
                  <div class="turn">
                    <span class="muted small">{{ i18n.t('nego.assistantOf', turn.partyDisplayName) }}</span>
                    @if (turn.publicMessage) {
                      <p class="turn-msg">{{ turn.publicMessage }}</p>
                    }
                    @if (turn.proposal; as proposal) {
                      <div class="proposal-card">
                        <strong>{{ i18n.t('nego.proposalTitle', proposal.title) }}</strong>
                        <ul>
                          @for (term of proposal.terms; track term) {
                            <li>{{ term }}</li>
                          }
                        </ul>
                        @if (proposal.openIssues.length > 0) {
                          <p class="muted small">{{ i18n.t('nego.openIssues') }}: {{ proposal.openIssues.join(' · ') }}</p>
                        }
                      </div>
                    }
                  </div>
                }
              </div>
            </details>
          }
        } @else if (canStart()) {
          <button class="btn btn-primary" type="button" [disabled]="busy()" (click)="start()">
            🤝 {{ i18n.t('nego.start') }}
          </button>
          <p class="muted small">{{ i18n.t('nego.startHint') }}</p>
        }
      </div>
    }
  `,
  styles: `
    .running { font-weight: 600; }
    .small { font-size: 0.85rem; }
    .question-box {
      background: var(--color-private-soft); border-radius: var(--radius); padding: var(--space-3);
      display: flex; flex-direction: column; gap: var(--space-2);
    }
    .q-text { margin: 0; font-weight: 600; }
    .answer-row { display: flex; gap: var(--space-2); flex-wrap: wrap; }
    .answer-row input { flex: 1; min-width: 200px; }
    .proposal-card {
      background: var(--color-primary-soft); border-radius: var(--radius); padding: var(--space-3);
    }
    .proposal-card ul { margin: var(--space-1) 0 0; padding-inline-start: var(--space-4); }
    .turns { display: flex; flex-direction: column; gap: var(--space-3); margin-block-start: var(--space-3); }
    .turn-msg { margin: var(--space-1) 0; white-space: pre-wrap; }
    summary { cursor: pointer; font-weight: 600; }
  `,
})
export class NegotiationPanelComponent {
  protected readonly i18n = inject(I18nService);
  private readonly negotiation = inject(NegotiationService);

  readonly roomId = input.required<string>();
  readonly roomStatus = input.required<RoomStatus>();

  protected readonly run = signal<NegotiationRunView | null>(null);
  protected readonly busy = signal(false);
  protected answers: Record<string, string> = {};

  protected readonly visible = computed(
    () => this.run() !== null || this.canStart(),
  );

  protected readonly canStart = computed(() => {
    const status = this.roomStatus();
    const current = this.run();
    const runInactive = !current || current.status === 'COMPLETED' || current.status === 'FAILED';
    return status === 'ACTIVE' && runInactive;
  });

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.negotiation.runs(this.roomId()).subscribe({
      next: (runs) => this.run.set(runs[0] ?? null),
      error: () => undefined, // observers/advisors: panel simply stays hidden
    });
  }

  protected start(): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.negotiation.start(this.roomId()).subscribe({
      next: () => {
        this.busy.set(false);
        this.refresh();
      },
      error: () => this.busy.set(false),
    });
  }

  protected pause(runId: string): void {
    this.negotiation.pause(this.roomId(), runId).subscribe({ next: () => this.refresh() });
  }

  protected resume(runId: string): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.negotiation.resume(this.roomId(), runId).subscribe({
      next: () => {
        this.busy.set(false);
        this.refresh();
      },
      error: () => this.busy.set(false),
    });
  }

  protected answer(questionId: string): void {
    const text = this.answers[questionId]?.trim();
    if (!text || this.busy()) return;
    this.busy.set(true);
    this.negotiation.answer(this.roomId(), questionId, text).subscribe({
      next: () => {
        delete this.answers[questionId];
        this.busy.set(false);
        this.refresh();
      },
      error: () => this.busy.set(false),
    });
  }
}
