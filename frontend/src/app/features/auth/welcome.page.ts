import { Component, NgZone, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { SocialLogin } from '@capgo/capacitor-social-login';
import { AuthService } from '../../core/auth.service';
import { I18nService } from '../../core/i18n.service';
import { isNativeApp } from '../../core/server-base';
import { DemoFlowComponent } from '../../shared/demo-flow.component';
import { DemoShotsComponent } from '../../shared/demo-shots.component';

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
  imports: [FormsModule, DemoFlowComponent, DemoShotsComponent],
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
          @if (native) {
            <!-- Native app: Google blocks its web flow in WebViews; use the system account picker. -->
            <button class="btn google-native" type="button" (click)="nativeGoogleSignIn()" [disabled]="busy()">
              <svg viewBox="0 0 48 48" width="20" height="20" aria-hidden="true"><path fill="#FFC107" d="M43.6 20.1H42V20H24v8h11.3C33.7 32.7 29.2 36 24 36c-6.6 0-12-5.4-12-12s5.4-12 12-12c3.1 0 5.9 1.2 8 3l5.7-5.7C34.3 6.1 29.4 4 24 4 13 4 4 13 4 24s9 20 20 20 20-9 20-20c0-1.3-.1-2.6-.4-3.9z"/><path fill="#FF3D00" d="m6.3 14.7 6.6 4.8C14.7 15.1 19 12 24 12c3.1 0 5.9 1.2 8 3l5.7-5.7C34.3 6.1 29.4 4 24 4 16.3 4 9.7 8.3 6.3 14.7z"/><path fill="#4CAF50" d="M24 44c5.2 0 9.9-2 13.4-5.2l-6.2-5.2C29.2 35.1 26.7 36 24 36c-5.2 0-9.6-3.3-11.3-8l-6.5 5C9.5 39.6 16.2 44 24 44z"/><path fill="#1976D2" d="M43.6 20.1H42V20H24v8h11.3c-.8 2.2-2.2 4.2-4.1 5.6l6.2 5.2C41 35.4 44 30.2 44 24c0-1.3-.1-2.6-.4-3.9z"/></svg>
              {{ i18n.t('auth.googleContinue') }}
            </button>
          } @else {
            <div id="googleButton" class="google-button"></div>
          }
        </div>
      </div>

      <app-demo-flow />
      <app-demo-shots />
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
    .google-native {
      display: flex; align-items: center; justify-content: center; gap: var(--space-2);
      inline-size: 100%; min-block-size: 44px;
      border: 1px solid var(--color-border); background: var(--color-bg); font-weight: 600;
    }
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
  protected readonly native = isNativeApp();

  protected email = '';
  protected displayName = '';
  protected password = '';

  ngOnInit(): void {
    // Google sign-in is optional: render the button only when the server has a client ID.
    this.auth.authConfig().subscribe({
      next: (config) => {
        if (!config.googleClientId) return;
        if (this.native) {
          void this.setUpNativeGoogle(config.googleClientId);
        } else {
          this.setUpGoogle(config.googleClientId);
        }
      },
      error: () => undefined,
    });
  }

  /** Native app: the system account picker returns an ID token our backend already verifies. */
  private async setUpNativeGoogle(webClientId: string): Promise<void> {
    await SocialLogin.initialize({ google: { webClientId } });
    this.googleReady.set(true);
  }

  protected async nativeGoogleSignIn(): Promise<void> {
    try {
      const response = await SocialLogin.login({ provider: 'google', options: {} });
      const idToken = (response.result as { idToken?: string | null }).idToken;
      if (idToken) {
        this.zone.run(() => this.onGoogleCredential(idToken));
      }
    } catch {
      // User dismissed the picker — not an error worth showing.
    }
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
