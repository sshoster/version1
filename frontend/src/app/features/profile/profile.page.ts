import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth.service';
import { AvatarService } from '../../core/files.service';
import { I18nService } from '../../core/i18n.service';
import { AvatarComponent } from '../../shared/avatar.component';

/** Account profile: change the photo and the account display name (email is the identity — read-only). */
@Component({
  selector: 'app-profile-page',
  imports: [FormsModule, AvatarComponent],
  template: `
    <div class="page stack narrow">
      <h1>{{ i18n.t('account.title') }}</h1>

      @if (auth.user(); as user) {
        <div class="card stack">
          <div class="photo-row">
            <app-avatar [userId]="user.id" [name]="user.displayName" [size]="88" />
            <div class="photo-actions">
              <label class="btn btn-secondary">
                {{ photoSaved() ? i18n.t('menu.avatarSaved') : i18n.t('account.changePhoto') }}
                <input type="file" hidden accept="image/png,image/jpeg,image/webp" (change)="onPhotoPicked($event)" />
              </label>
              <p class="muted small">{{ i18n.t('account.photoHint') }}</p>
            </div>
          </div>

          <form class="stack" (ngSubmit)="save()">
            <label for="displayName">{{ i18n.t('auth.displayName') }}</label>
            <input id="displayName" name="displayName" type="text" maxlength="80" [(ngModel)]="displayName" required />

            <label for="email">{{ i18n.t('auth.email') }}</label>
            <input id="email" name="email" type="email" [value]="user.email" disabled />
            <p class="muted small">{{ i18n.t('account.emailHint') }}</p>

            @if (error()) {
              <div class="error-box" role="alert">{{ error() }}</div>
            }
            <button class="btn btn-primary" type="submit" [disabled]="busy() || !displayName.trim()">
              {{ saved() ? i18n.t('account.saved') : i18n.t('account.save') }}
            </button>
          </form>

          <p class="muted small">{{ i18n.t('account.roomNamesNote') }}</p>
        </div>
      }
    </div>
  `,
  styles: `
    .narrow { max-width: 560px; margin-inline: auto; }
    .photo-row { display: flex; align-items: center; gap: var(--space-4); }
    .photo-actions { display: flex; flex-direction: column; gap: var(--space-1); }
    .photo-actions .btn { cursor: pointer; align-self: flex-start; }
    .small { font-size: 0.8rem; margin: 0; }
    form label { font-weight: 600; }
    input:disabled { opacity: 0.7; }
  `,
})
export class ProfilePage {
  protected readonly i18n = inject(I18nService);
  protected readonly auth = inject(AuthService);
  private readonly avatars = inject(AvatarService);

  protected readonly busy = signal(false);
  protected readonly saved = signal(false);
  protected readonly photoSaved = signal(false);
  protected readonly error = signal<string | null>(null);
  protected displayName = this.auth.user()?.displayName ?? '';

  protected save(): void {
    const name = this.displayName.trim();
    if (!name || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    this.auth.updateProfile(name).subscribe({
      next: () => {
        this.busy.set(false);
        this.saved.set(true);
        setTimeout(() => this.saved.set(false), 2000);
      },
      error: (err: { error?: { message?: string } }) => {
        this.busy.set(false);
        this.error.set(err?.error?.message ?? this.i18n.t('auth.genericError'));
      },
    });
  }

  protected onPhotoPicked(event: Event): void {
    const inputElement = event.target as HTMLInputElement;
    const file = inputElement.files?.[0];
    inputElement.value = '';
    const userId = this.auth.user()?.id;
    if (!file || !userId) return;
    this.avatars.uploadMine(file).subscribe({
      next: () => {
        this.avatars.invalidate(userId);
        this.photoSaved.set(true);
        setTimeout(() => this.photoSaved.set(false), 1500);
        // Reload so every avatar instance refetches.
        location.reload();
      },
      error: () => this.error.set(this.i18n.t('auth.genericError')),
    });
  }
}
