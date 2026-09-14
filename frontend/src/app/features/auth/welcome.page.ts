import { Component, NgZone, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { I18nService } from '../../core/i18n.service';

/** Google Identity Services global, loaded on demand from accounts.google.com. */
declare const google: {
  accounts: {
    id: {
      initialize(config: { client_id: string; callback: (response: { credential: string }) => void }): void;
      renderButton(parent: HTMLElement, options: Record<string, unknown>): void;
    };
  };
};

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

        <div class="google-area" [hidden]="!googleReady()">
          <div class="divider"><span>{{ i18n.t('auth.orDivider') }}</span></div>
          <div id="googleButton" class="google-button"></div>
        </div>
      </div>
    </div>
  `,
  styles: `
    .divider {
      display: flex; align-items: center; gap: var(--space-2);
      color: var(--color-text-muted); font-size: 0.85rem; margin-block: var(--space-2);
    }
    .divider::before, .divider::after {
      content: ''; flex: 1; border-block-start: 1px solid var(--color-border);
    }
    .google-button { display: flex; justify-content: center; min-height: 44px; }
  `,
})
export class WelcomePage {
  protected readonly i18n = inject(I18nService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly zone = inject(NgZone);

  protected readonly mode = signal<'login' | 'register'>('login');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly googleReady = signal(false);

  protected email = '';
  protected displayName = '';
  protected password = '';

  ngOnInit(): void {
    // Google sign-in is optional: render the button only when the server has a client ID.
    this.auth.authConfig().subscribe({
      next: (config) => {
        if (config.googleClientId) this.setUpGoogle(config.googleClientId);
      },
      error: () => undefined,
    });
  }

  private setUpGoogle(clientId: string): void {
    const existing = document.getElementById('google-gsi-script');
    if (existing && typeof google !== 'undefined') {
      this.renderGoogleButton(clientId);
      return;
    }
    const script = document.createElement('script');
    script.id = 'google-gsi-script';
    script.src = 'https://accounts.google.com/gsi/client';
    script.async = true;
    script.onload = () => this.zone.run(() => this.renderGoogleButton(clientId));
    document.head.appendChild(script);
  }

  private renderGoogleButton(clientId: string): void {
    const parent = document.getElementById('googleButton');
    if (!parent) return;
    google.accounts.id.initialize({
      client_id: clientId,
      callback: (response) => this.zone.run(() => this.onGoogleCredential(response.credential)),
    });
    google.accounts.id.renderButton(parent, {
      theme: 'outline',
      size: 'large',
      width: 280,
      locale: this.i18n.lang() === 'he' ? 'iw' : 'en',
    });
    this.googleReady.set(true);
  }

  private onGoogleCredential(credential: string): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    this.auth.loginWithGoogle(credential).subscribe({
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
