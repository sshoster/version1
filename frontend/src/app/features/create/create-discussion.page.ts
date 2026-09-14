import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { switchMap, of, map, catchError } from 'rxjs';
import { I18nService } from '../../core/i18n.service';
import { RoomsService } from '../../core/rooms.service';
import { DemoFlowComponent } from '../../shared/demo-flow.component';
import { DemoShotsComponent } from '../../shared/demo-shots.component';

/**
 * Conversational creation: three short questions, one at a time (design doc §11.1).
 * Q1 topic (required) → Q2 context (skippable) → Q3 invite email (skippable).
 */
@Component({
  selector: 'app-create-discussion-page',
  imports: [FormsModule, DemoFlowComponent, DemoShotsComponent],
  template: `
    <div class="page">
      <div class="card stack">
        <p class="muted">{{ i18n.t('create.stepOf', step(), 3) }}</p>

        @if (error()) {
          <div class="error-box" role="alert">{{ error() }}</div>
        }

        @switch (step()) {
          @case (1) {
            <h1>{{ i18n.t('create.q1') }}</h1>
            <div class="field">
              <label class="hint" for="title">{{ i18n.t('create.q1.hint') }}</label>
              <input id="title" name="title" type="text" [(ngModel)]="title" required maxlength="200" />
            </div>
            <button class="btn btn-primary" type="button" [disabled]="!title.trim()" (click)="step.set(2)">
              {{ i18n.t('create.next') }}
            </button>
          }
          @case (2) {
            <h1>{{ i18n.t('create.q2') }}</h1>
            <div class="field">
              <label class="hint" for="objective">{{ i18n.t('create.q2.hint') }}</label>
              <textarea id="objective" name="objective" rows="4" [(ngModel)]="objective" maxlength="4000"></textarea>
            </div>
            <div class="actions">
              <button class="btn btn-secondary" type="button" (click)="step.set(1)">{{ i18n.t('create.back') }}</button>
              <button class="btn btn-quiet" type="button" (click)="objective = ''; step.set(3)">{{ i18n.t('create.skip') }}</button>
              <button class="btn btn-primary" type="button" (click)="step.set(3)">{{ i18n.t('create.next') }}</button>
            </div>
          }
          @case (3) {
            <h1>{{ i18n.t('create.q3') }}</h1>
            <div class="field">
              <label class="hint" for="inviteEmail">{{ i18n.t('create.q3.hint') }}</label>
              <input id="inviteEmail" name="inviteEmail" type="email" dir="ltr" [(ngModel)]="inviteEmail" />
            </div>
            <div class="actions">
              <button class="btn btn-secondary" type="button" (click)="step.set(2)">{{ i18n.t('create.back') }}</button>
              <button class="btn btn-quiet" type="button" [disabled]="busy()" (click)="inviteEmail = ''; finish()">
                {{ i18n.t('create.skip') }}
              </button>
              <button class="btn btn-primary" type="button" [disabled]="busy()" (click)="finish()">
                {{ i18n.t('create.finish') }}
              </button>
            </div>
          }
        }
      </div>

      @if (step() === 1) {
        <app-demo-flow />
        <app-demo-shots />
      }
    </div>
  `,
  styles: `
    .actions { display: flex; flex-wrap: wrap; gap: var(--space-2); }
    .actions .btn-primary { margin-inline-start: auto; }
  `,
})
export class CreateDiscussionPage {
  protected readonly i18n = inject(I18nService);
  private readonly rooms = inject(RoomsService);
  private readonly router = inject(Router);

  protected readonly step = signal<1 | 2 | 3>(1);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected title = '';
  protected objective = '';
  protected inviteEmail = '';

  protected finish(): void {
    if (this.busy() || !this.title.trim()) return;
    this.busy.set(true);
    this.error.set(null);

    const email = this.inviteEmail.trim() || null;
    this.rooms
      .create(this.title.trim(), this.objective.trim() || null)
      .pipe(
        switchMap((room) => {
          if (!email) return of(room.id);
          // Invitation failure should not lose the created room — land on the room page either way.
          return this.rooms.invite(room.id, 'PARTY', email).pipe(
            map(() => room.id),
            catchError(() => of(room.id)),
          );
        }),
      )
      .subscribe({
        next: (roomId) => void this.router.navigate(['/rooms', roomId]),
        error: (err: { error?: { message?: string } }) => {
          this.busy.set(false);
          this.error.set(err?.error?.message ?? this.i18n.t('auth.genericError'));
        },
      });
  }
}
