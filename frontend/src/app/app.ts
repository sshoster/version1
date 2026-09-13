import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth.service';
import { AvatarService } from './core/files.service';
import { I18nService } from './core/i18n.service';
import { AvatarComponent } from './shared/avatar.component';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, AvatarComponent],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  protected readonly i18n = inject(I18nService);
  protected readonly auth = inject(AuthService);
  private readonly avatars = inject(AvatarService);
  private readonly router = inject(Router);

  protected readonly menuOpen = signal(false);
  protected readonly avatarSaved = signal(false);

  protected onAvatarPicked(event: Event): void {
    const inputElement = event.target as HTMLInputElement;
    const file = inputElement.files?.[0];
    inputElement.value = '';
    const userId = this.auth.user()?.id;
    if (!file || !userId) return;
    this.avatars.uploadMine(file).subscribe({
      next: () => {
        this.avatars.invalidate(userId);
        this.avatarSaved.set(true);
        setTimeout(() => {
          this.avatarSaved.set(false);
          this.menuOpen.set(false);
        }, 1500);
        // Reload so every avatar instance refetches.
        location.reload();
      },
    });
  }

  protected toggleLanguage(): void {
    this.i18n.setLang(this.i18n.lang() === 'he' ? 'en' : 'he');
  }

  protected signOut(): void {
    this.auth.logout();
    void this.router.navigate(['/welcome']);
  }
}
