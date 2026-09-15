import { Component, effect, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { I18nService } from '../../core/i18n.service';
import { InvitationPublicInfo } from '../../core/models';
import { RoomsService } from '../../core/rooms.service';
import { DemoFlowComponent } from '../../shared/demo-flow.component';

@Component({
  selector: 'app-accept-invite-page',
  imports: [RouterLink, DemoFlowComponent],
  template: `
    <div class="page">
      <div class="card stack">
        @if (loading()) {
          <p class="muted">{{ i18n.t('common.loading') }}</p>
        } @else if (info(); as invitation) {
          <h1>
            {{ i18n.t('invite.title') }}@if (invitation.invitedName) {, {{ invitation.invitedName }}} 💬
          </h1>
          <p><strong>{{ i18n.t('invite.roomLabel') }}:</strong> {{ invitation.roomTitle }}</p>
          <p><strong>{{ i18n.t('invite.invitedBy') }}:</strong> {{ invitation.invitedBy }}</p>
          <p><strong>{{ i18n.t('invite.asRole') }}:</strong> {{ i18n.t('room.role.' + invitation.role) }}</p>

          @if (invitation.status === 'PENDING') {
            @if (auth.isAuthenticated()) {
              <!-- Joining always happens under the SIGNED-IN account — make that unmissable,
                   so an invitee on a shared browser doesn't accept as someone else. -->
              <p class="signed-in muted">
                👤 {{ i18n.t('invite.signedInAs', auth.user()?.displayName ?? '', auth.user()?.email ?? '') }}
              </p>
              @if (error()) {
                <div class="error-box" role="alert">{{ error() }}</div>
              }
              <div class="accept-row">
                <button class="btn btn-primary" type="button" [disabled]="busy()" (click)="accept()">
                  {{ i18n.t('invite.accept') }}
                </button>
                <button class="btn btn-quiet" type="button" (click)="switchAccount()">
                  {{ i18n.t('invite.notMe') }}
                </button>
              </div>
            } @else {
              <p class="muted">{{ i18n.t('invite.needAccount') }}</p>
              <a class="btn btn-primary" [routerLink]="['/welcome']" [queryParams]="{ returnUrl: currentUrl }">
                {{ i18n.t('auth.signInAction') }}
              </a>
            }
          } @else if (invitation.status === 'EXPIRED') {
            <div class="error-box">{{ i18n.t('invite.expired') }}</div>
          } @else {
            <div class="error-box">{{ i18n.t('invite.used') }}</div>
          }
        } @else {
          <div class="error-box">{{ i18n.t('invite.used') }}</div>
        }
      </div>

      <app-demo-flow />
    </div>
  `,
  styles: `
    .signed-in {
      background: var(--color-bg); border-radius: var(--radius);
      padding: var(--space-2) var(--space-3); margin: 0;
    }
    .accept-row { display: flex; gap: var(--space-2); flex-wrap: wrap; align-items: center; }
  `,
})
export class AcceptInvitePage {
  protected readonly i18n = inject(I18nService);
  protected readonly auth = inject(AuthService);
  private readonly rooms = inject(RoomsService);
  private readonly router = inject(Router);

  readonly token = input.required<string>();

  protected readonly loading = signal(true);
  protected readonly info = signal<InvitationPublicInfo | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected get currentUrl(): string {
    return `/invite/${this.token()}`;
  }

  /** Shared-browser case: sign out and come back to this invitation with the right account. */
  protected switchAccount(): void {
    this.auth.logout();
    this.error.set(null);
    void this.router.navigate(['/welcome'], { queryParams: { returnUrl: this.currentUrl } });
  }

  constructor() {
    effect(() => {
      const token = this.token();
      this.loading.set(true);
      this.rooms.invitationInfo(token).subscribe({
        next: (info) => {
          this.info.set(info);
          this.loading.set(false);
        },
        error: () => {
          this.info.set(null);
          this.loading.set(false);
        },
      });
    });
  }

  protected accept(): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    this.rooms.acceptInvitation(this.token()).subscribe({
      next: ({ roomId }) => void this.router.navigate(['/rooms', roomId]),
      error: (err: { error?: { code?: string; message?: string } }) => {
        this.busy.set(false);
        if (err?.error?.code === 'ALREADY_MEMBER') {
          this.error.set(this.i18n.t('invite.alreadyMember'));
        } else {
          this.error.set(err?.error?.message ?? this.i18n.t('auth.genericError'));
        }
      },
    });
  }
}
