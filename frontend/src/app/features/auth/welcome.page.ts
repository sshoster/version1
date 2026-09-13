import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { I18nService } from '../../core/i18n.service';

@Component({
  selector: 'app-welcome-page',
  imports: [FormsModule],
  template: `
    <div class="page">
      <div class="card stack">
        <h1>{{ i18n.t('auth.welcome') }} 👋</h1>
        <p class="muted">{{ i18n.t('auth.intro') }}</p>

        @if (error()) {
          <div class="error-box" role="alert">{{ error() }}</div>
        }

        <form (ngSubmit)="submit()">
          @if (mode() === 'register') {
            <div class="field">
              <label for="displayName">{{ i18n.t('auth.displayName') }}</label>
              <input id="displayName" name="displayName" type="text" required
                     [(ngModel)]="displayName" autocomplete="name" />
            </div>
          }
          <div class="field">
            <label for="email">{{ i18n.t('auth.email') }}</label>
            <input id="email" name="email" type="email" required dir="ltr"
                   [(ngModel)]="email" autocomplete="email" />
          </div>
          <div class="field">
            <label for="password">{{ i18n.t('auth.password') }}</label>
            <input id="password" name="password" type="password" required dir="ltr"
                   [(ngModel)]="password"
                   [attr.autocomplete]="mode() === 'register' ? 'new-password' : 'current-password'" />
            @if (mode() === 'register') {
              <span class="hint">{{ i18n.t('auth.passwordHint') }}</span>
            }
          </div>
          <button class="btn btn-primary" type="submit" [disabled]="busy()" style="width: 100%">
            {{ mode() === 'register' ? i18n.t('auth.registerAction') : i18n.t('auth.signInAction') }}
          </button>
        </form>

        <button class="btn btn-quiet" type="button" (click)="toggleMode()">
          {{ mode() === 'register' ? i18n.t('auth.switchToLogin') : i18n.t('auth.switchToRegister') }}
        </button>
      </div>
    </div>
  `,
})
export class WelcomePage {
  protected readonly i18n = inject(I18nService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly mode = signal<'login' | 'register'>('login');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected email = '';
  protected displayName = '';
  protected password = '';

  protected toggleMode(): void {
    this.error.set(null);
    this.mode.update((m) => (m === 'login' ? 'register' : 'login'));
  }

  protected submit(): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    const request =
      this.mode() === 'register'
        ? this.auth.register(this.email, this.displayName, this.password)
        : this.auth.login(this.email, this.password);
    request.subscribe({
      next: () => {
        const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl');
        void this.router.navigateByUrl(returnUrl && returnUrl.startsWith('/') ? returnUrl : '/');
      },
      error: (err: { error?: { message?: string } }) => {
        this.busy.set(false);
        this.error.set(err?.error?.message ?? this.i18n.t('auth.genericError'));
      },
    });
  }
}
