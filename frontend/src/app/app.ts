import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth.service';
import { AvatarService } from './core/files.service';
import { I18nService } from './core/i18n.service';
import { RoomTool, RoomUiService } from './core/room-ui.service';
import { RoomsStore } from './core/rooms-store.service';
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
  protected readonly roomUi = inject(RoomUiService);
  protected readonly roomsStore = inject(RoomsStore);
  private readonly avatars = inject(AvatarService);
  private readonly router = inject(Router);

  constructor() {
    // Pick up server-side profile changes (e.g. the super-admin flag) on every app start.
    if (this.auth.isAuthenticated()) this.auth.loadMe();
  }

  protected readonly drawerOpen = signal(false);
  /** The drawer's rooms sub-list: open by default, collapsible by the user. */
  protected readonly roomsListOpen = signal(true);
  protected readonly profileOpen = signal(false);
  protected readonly avatarSaved = signal(false);

  protected toggleDrawer(): void {
    this.profileOpen.set(false);
    this.drawerOpen.set(!this.drawerOpen());
    // The drawer lists the user's rooms — serve from cache, refetch only when stale.
    if (this.drawerOpen() && this.auth.isAuthenticated()) this.roomsStore.ensureFresh();
  }

  protected toggleProfile(): void {
    this.drawerOpen.set(false);
    this.profileOpen.set(!this.profileOpen());
  }

  protected closeAll(): void {
    this.drawerOpen.set(false);
    this.profileOpen.set(false);
  }

  /** Drawer entry for a room tool: close the menu, slide the tool panel in. */
  protected openRoomTool(tool: RoomTool): void {
    this.closeAll();
    this.roomUi.openTool.set(tool);
  }

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
          this.profileOpen.set(false);
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
    this.closeAll();
    this.roomsStore.clear();
    this.auth.logout();
    void this.router.navigate(['/welcome']);
  }
}
