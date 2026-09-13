import { Component, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { I18nService } from '../../core/i18n.service';
import { ApprovalRequestView, ProposalView } from '../../core/models';
import { ProposalContent, ProposalsService } from '../../core/proposals.service';

/**
 * Formal proposals: exact-version approvals with fair-weight Approve / Reject / Request-changes
 * (design doc §11.5 — no dark patterns), revision with explicit consequence, version history.
 */
@Component({
  selector: 'app-proposals-panel',
  imports: [FormsModule],
  template: `
    @if (proposals().length > 0) {
      <div class="card stack">
        <h2>{{ i18n.t('prop.title') }}</h2>
        @if (error()) {
          <div class="error-box" role="alert">{{ error() }}</div>
        }
        @for (proposal of proposals(); track proposal.id) {
          <article class="proposal" [class.agreed]="proposal.status === 'AGREED'">
            <header class="p-head">
              <strong>{{ proposal.current.title }}</strong>
              <span class="badge">{{ i18n.t('prop.status.' + proposal.status) }}</span>
              <span class="muted small">{{ i18n.t('prop.version', proposal.currentVersion) }}</span>
            </header>
            <ul class="terms">
              @for (term of proposal.current.terms; track term) {
                <li>{{ term }}</li>
              }
            </ul>
            @if (proposal.current.assumptions.length > 0) {
              <p class="muted small">{{ i18n.t('nego.assumptions') }}: {{ proposal.current.assumptions.join(' · ') }}</p>
            }

            @if (proposal.pendingRequest; as request) {
              <div class="approval-box">
                @for (approval of request.approvals; track approval.userId) {
                  <p class="small decision-line">
                    @switch (approval.decision) {
                      @case ('APPROVED') { ✅ {{ i18n.t('prop.approvedBy', approval.userDisplayName, approval.proposalVersion) }} }
                      @case ('REJECTED') { ❌ {{ i18n.t('prop.rejectedBy', approval.userDisplayName) }} }
                      @case ('CHANGES_REQUESTED') { ✏️ {{ i18n.t('prop.changesBy', approval.userDisplayName) }} }
                    }
                    @if (approval.comment) { — <em>{{ approval.comment }}</em> }
                  </p>
                }
                @if (request.myDecision) {
                  <p><strong>{{ i18n.t('prop.decided.' + request.myDecision) }}</strong></p>
                  @if (request.status === 'PENDING') {
                    <p class="muted small">{{ i18n.t('prop.waitingOthers') }}</p>
                  }
                } @else {
                  <p><strong>{{ i18n.t('prop.yourApprovalNeeded') }}</strong></p>
                  <input
                    type="text" name="comment-{{ request.id }}"
                    [placeholder]="i18n.t('prop.commentPlaceholder')" [(ngModel)]="comments[request.id]"
                  />
                  <!-- Equal visual weight on purpose: no dark patterns toward approval. -->
                  <div class="decide-row">
                    <button class="btn btn-secondary" type="button" [disabled]="busy()" (click)="decide(proposal, request, 'approve')">
                      {{ i18n.t('prop.approve') }}
                    </button>
                    <button class="btn btn-secondary" type="button" [disabled]="busy()" (click)="decide(proposal, request, 'reject')">
                      {{ i18n.t('prop.reject') }}
                    </button>
                    <button class="btn btn-secondary" type="button" [disabled]="busy()" (click)="decide(proposal, request, 'request-changes')">
                      {{ i18n.t('prop.requestChanges') }}
                    </button>
                  </div>
                }
              </div>
            } @else if (proposal.status === 'OPEN' && isParty()) {
              <div class="actions-row">
                <button class="btn btn-primary" type="button" [disabled]="busy()" (click)="requestApproval(proposal)">
                  {{ i18n.t('prop.requestApproval') }}
                </button>
                <button class="btn btn-quiet" type="button" (click)="startEdit(proposal)">
                  {{ i18n.t('prop.revise') }}
                </button>
              </div>
              <p class="muted small">{{ i18n.t('prop.requestExplain') }}</p>
            }

            @if (editingId() === proposal.id) {
              <div class="edit-box stack">
                <p class="muted small">{{ i18n.t('prop.reviseNote') }}</p>
                <div class="field">
                  <label for="edit-title">{{ i18n.t('prop.titleLabel') }}</label>
                  <input id="edit-title" name="editTitle" type="text" [(ngModel)]="editTitle" />
                </div>
                <div class="field">
                  <label for="edit-terms">{{ i18n.t('prop.termsLabel') }}</label>
                  <textarea id="edit-terms" name="editTerms" rows="4" [(ngModel)]="editTerms"></textarea>
                </div>
                <div class="field">
                  <label for="edit-assumptions">{{ i18n.t('prop.assumptionsLabel') }}</label>
                  <textarea id="edit-assumptions" name="editAssumptions" rows="2" [(ngModel)]="editAssumptions"></textarea>
                </div>
                <div class="actions-row">
                  <button class="btn btn-quiet" type="button" (click)="editingId.set(null)">{{ i18n.t('common.cancel') }}</button>
                  <button class="btn btn-primary" type="button" [disabled]="busy() || !editTitle.trim() || !editTerms.trim()" (click)="saveRevision(proposal)">
                    {{ i18n.t('prop.save') }}
                  </button>
                </div>
              </div>
            }

            @if (proposal.versions.length > 1) {
              <details>
                <summary>{{ i18n.t('prop.history') }}</summary>
                @for (version of proposal.versions; track version.version) {
                  @if (version.superseded) {
                    <div class="old-version">
                      <span class="muted small">
                        {{ i18n.t('prop.version', version.version) }} · {{ version.createdByDisplayName }}
                      </span>
                      <p class="small"><strong>{{ version.title }}</strong> — {{ version.terms.join(' · ') }}</p>
                    </div>
                  }
                }
              </details>
            }
          </article>
        }
      </div>
    }
  `,
  styles: `
    .proposal { border: 1px solid var(--color-border); border-radius: var(--radius); padding: var(--space-3); }
    .proposal.agreed { border-color: var(--color-primary); background: var(--color-primary-soft); }
    .p-head { display: flex; align-items: center; gap: var(--space-2); flex-wrap: wrap; }
    .terms { margin: var(--space-2) 0; padding-inline-start: var(--space-4); }
    .small { font-size: 0.85rem; }
    .approval-box { background: var(--color-bg); border-radius: var(--radius); padding: var(--space-3); display: flex; flex-direction: column; gap: var(--space-2); }
    .decide-row, .actions-row { display: flex; gap: var(--space-2); flex-wrap: wrap; }
    .decision-line { margin: 0; }
    .edit-box { background: var(--color-bg); border-radius: var(--radius); padding: var(--space-3); margin-block-start: var(--space-2); }
    .old-version { border-inline-start: 3px solid var(--color-border); padding-inline-start: var(--space-2); margin-block-start: var(--space-2); }
    summary { cursor: pointer; font-weight: 600; margin-block-start: var(--space-2); }
  `,
})
export class ProposalsPanelComponent {
  protected readonly i18n = inject(I18nService);
  private readonly service = inject(ProposalsService);

  readonly roomId = input.required<string>();
  readonly isParty = input.required<boolean>();

  protected readonly proposals = signal<ProposalView[]>([]);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly editingId = signal<string | null>(null);
  protected comments: Record<string, string> = {};
  protected editTitle = '';
  protected editTerms = '';
  protected editAssumptions = '';

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.service.list(this.roomId()).subscribe({
      next: (proposals) => this.proposals.set(proposals),
      error: () => undefined,
    });
  }

  createFrom(content: ProposalContent): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.service.create(this.roomId(), content).subscribe({
      next: () => {
        this.busy.set(false);
        this.refresh();
      },
      error: () => this.busy.set(false),
    });
  }

  protected requestApproval(proposal: ProposalView): void {
    this.busy.set(true);
    this.error.set(null);
    this.service.requestApproval(this.roomId(), proposal.id).subscribe({
      next: () => {
        this.busy.set(false);
        this.refresh();
      },
      error: (err: { error?: { message?: string } }) => {
        this.busy.set(false);
        this.error.set(err?.error?.message ?? this.i18n.t('auth.genericError'));
      },
    });
  }

  protected decide(proposal: ProposalView, request: ApprovalRequestView, decision: 'approve' | 'reject' | 'request-changes'): void {
    this.busy.set(true);
    this.error.set(null);
    this.service
      .decide(this.roomId(), request.id, decision, proposal.currentVersion, this.comments[request.id]?.trim() || undefined)
      .subscribe({
        next: () => {
          delete this.comments[request.id];
          this.busy.set(false);
          this.refresh();
        },
        error: (err: { status?: number; error?: { message?: string } }) => {
          this.busy.set(false);
          this.error.set(err?.status === 409 ? this.i18n.t('prop.conflict') : (err?.error?.message ?? this.i18n.t('auth.genericError')));
          this.refresh();
        },
      });
  }

  protected startEdit(proposal: ProposalView): void {
    this.editingId.set(proposal.id);
    this.editTitle = proposal.current.title;
    this.editTerms = proposal.current.terms.join('\n');
    this.editAssumptions = proposal.current.assumptions.join('\n');
  }

  protected saveRevision(proposal: ProposalView): void {
    this.busy.set(true);
    this.error.set(null);
    this.service
      .revise(this.roomId(), proposal.id, {
        title: this.editTitle.trim(),
        terms: this.editTerms.split('\n').map((line) => line.trim()).filter(Boolean),
        assumptions: this.editAssumptions.split('\n').map((line) => line.trim()).filter(Boolean),
      })
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.editingId.set(null);
          this.refresh();
        },
        error: (err: { error?: { message?: string } }) => {
          this.busy.set(false);
          this.error.set(err?.error?.message ?? this.i18n.t('auth.genericError'));
        },
      });
  }
}
