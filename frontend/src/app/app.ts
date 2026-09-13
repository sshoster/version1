import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth.service';
import { I18nService } from './core/i18n.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  protected readonly i18n = inject(I18nService);
  protected readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly menuOpen = signal(false);

  protected toggleLanguage(): void {
    this.i18n.setLang(this.i18n.lang() === 'he' ? 'en' : 'he');
  }

  protected signOut(): void {
    this.auth.logout();
    void this.router.navigate(['/welcome']);
  }
}
