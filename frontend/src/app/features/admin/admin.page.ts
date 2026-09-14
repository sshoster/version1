import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AdminService } from '../../core/admin.service';
import { AuthService } from '../../core/auth.service';
import { I18nService } from '../../core/i18n.service';
import { AdminRoomView, AdminUserView } from '../../core/models';

/**
 * Super-admin dashboard: user lifecycle (create / edit / suspend / delete) and room removal.
 * The server allowlists admins by email (SUPER_ADMIN_EMAILS) and 404s everyone else, so this
 * page is a convenience shell — authorization never depends on it.
 */
@Component({
  selector: 'app-admin-page',
  imports: [FormsModule, DatePipe],
  template: `
    <div class="page stack">
      <h1>🛠️ {{ i18n.t('admin.title') }}</h1>

      @if (error()) {
        <div class="error-box" role="alert">{{ error() }}</div>
      }

      <div class="card stack">
        <div class="head-row">
          <h2>{{ i18n.t('admin.users') }}</h2>
          <input
            class="search" type="text" name="search" [placeholder]="i18n.t('admin.search')"
            [(ngModel)]="search" (ngModelChange)="loadUsers()"
          />
        </div>

        <form class="add-row" (ngSubmit)="createUser()">
          <input type="text" name="newName" [placeholder]="i18n.t('auth.displayName')" [(ngModel)]="newName" />
          <input type="email" name="newEmail" dir="ltr" [placeholder]="i18n.t('auth.email')" [(ngModel)]="newEmail" />
          <input type="password" name="newPassword" dir="ltr" [placeholder]="i18n.t('auth.password')" [(ngModel)]="newPassword" autocomplete="new-password" />
          <button class="btn btn-primary" type="submit" [disabled]="busy() || !newEmail.trim() || !newName.trim() || newPassword.length < 5">
            {{ i18n.t('admin.addUser') }}
          </button>
        </form>

        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>{{ i18n.t('auth.displayName') }}</th>
                <th>{{ i18n.t('auth.email') }}</th>
                <th>{{ i18n.t('admin.state') }}</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              @for (user of users(); track user.id) {
                <tr [class.suspended]="user.suspended">
                  <td>
                    @if (editingId() === user.id) {
                      <input type="text" name="editName-{{ user.id }}" [(ngModel)]="editName" />
                      <input type="password" name="editPass-{{ user.id }}" dir="ltr"
                             [placeholder]="i18n.t('admin.newPasswordOptional')" [(ngModel)]="editPassword" autocomplete="new-password" />
                    } @else {
                      {{ user.displayName }}
                    }
                  </td>
                  <td dir="ltr" class="email">{{ user.email }}</td>
                  <td>{{ user.suspended ? i18n.t('admin.suspended') : i18n.t('admin.active') }}</td>
                  <td class="actions">
                    @if (editingId() === user.id) {
                      <button class="btn btn-primary mini" type="button" [disabled]="busy()" (click)="saveEdit(user)">{{ i18n.t('account.save') }}</button>
                      <button class="btn btn-quiet mini" type="button" (click)="editingId.set(null)">{{ i18n.t('common.close') }}</button>
                    } @else {
                      <button class="btn btn-quiet mini" type="button" (click)="startEdit(user)">{{ i18n.t('admin.edit') }}</button>
                      @if (user.id !== myId) {
                        @if (user.suspended) {
                          <button class="btn btn-secondary mini" type="button" [disabled]="busy()" (click)="reactivate(user)">{{ i18n.t('admin.reactivate') }}</button>
                        } @else {
                          <button class="btn btn-quiet mini" type="button" [disabled]="busy()" (click)="suspend(user)">{{ i18n.t('admin.suspend') }}</button>
                        }
                        <button class="btn btn-quiet mini danger" type="button" [disabled]="busy()" (click)="deleteUser(user)">{{ i18n.t('admin.delete') }}</button>
                      }
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </div>

      <div class="card stack">
        <h2>{{ i18n.t('admin.rooms') }}</h2>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>{{ i18n.t('admin.roomTitle') }}</th>
                <th>{{ i18n.t('join.codeLabel') }}</th>
                <th>{{ i18n.t('admin.state') }}</th>
                <th>{{ i18n.t('admin.created') }}</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              @for (room of rooms(); track room.id) {
                <tr>
                  <td>{{ room.title }}</td>
                  <td>
                    @if (room.joinCode) {
                      <button class="code-chip" type="button" (click)="copyCode(room)"
                              [title]="i18n.t('admin.copyCode')">
                        {{ copied() === room.id + ':code' ? i18n.t('admin.copied') : room.joinCode + ' ⧉' }}
                      </button>
                      <button class="btn btn-quiet mini" type="button" (click)="copyJoinLink(room)"
                              [title]="i18n.t('admin.copyLink')">
                        {{ copied() === room.id + ':link' ? i18n.t('admin.copied') : '🔗 ' + i18n.t('admin.copyLink') }}
                      </button>
                    }
                  </td>
                  <td>{{ i18n.t('status.' + room.status) }}</td>
                  <td>{{ room.createdAt | date: 'short' }}</td>
                  <td class="actions">
                    <button class="btn btn-quiet mini danger" type="button" [disabled]="busy()" (click)="deleteRoom(room)">
                      {{ i18n.t('admin.delete') }}
                    </button>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </div>
    </div>
  `,
  styles: `
    .head-row { display: flex; align-items: center; justify-content: space-between; gap: var(--space-2); flex-wrap: wrap; }
    .head-row h2, .card h2 { margin: 0; font-size: 1.05rem; }
    .search { max-inline-size: 240px; }
    .add-row { display: flex; gap: var(--space-2); flex-wrap: wrap; }
    .add-row input { flex: 1; min-inline-size: 160px; }
    .table-wrap { overflow-x: auto; }
    table { inline-size: 100%; border-collapse: collapse; font-size: 0.9rem; }
    th { text-align: start; color: var(--color-text-muted); font-weight: 600; font-size: 0.8rem; }
    th, td { padding: 8px 10px; border-block-end: 1px solid var(--color-border); }
    tr.suspended td { opacity: 0.55; }
    .email { font-size: 0.82rem; }
    .actions { display: flex; gap: 4px; flex-wrap: wrap; }
    .mini { min-height: 32px; padding: 0 10px; font-size: 0.8rem; }
    .danger { color: var(--color-danger); }
    .code-chip {
      border: 1px dashed var(--color-border); background: var(--color-bg);
      border-radius: 999px; cursor: pointer; padding: 3px 10px;
      font-family: monospace; font-size: 0.85rem; direction: ltr;
    }
    .code-chip:hover { border-color: var(--color-primary); }
    td input { margin-block: 2px; inline-size: 100%; max-inline-size: 220px; display: block; }
  `,
})
export class AdminPage {
  protected readonly i18n = inject(I18nService);
  private readonly admin = inject(AdminService);
  private readonly auth = inject(AuthService);

  protected readonly users = signal<AdminUserView[]>([]);
  protected readonly rooms = signal<AdminRoomView[]>([]);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly editingId = signal<string | null>(null);

  protected readonly myId = this.auth.user()?.id ?? '';
  protected search = '';
  protected newName = '';
  protected newEmail = '';
  protected newPassword = '';
  protected editName = '';
  protected editPassword = '';

  ngOnInit(): void {
    this.loadUsers();
    this.loadRooms();
  }

  protected loadUsers(): void {
    this.admin.users(this.search).subscribe({
      next: (users) => this.users.set(users),
      error: () => this.error.set(this.i18n.t('admin.noAccess')),
    });
  }

  private loadRooms(): void {
    this.admin.rooms().subscribe({ next: (rooms) => this.rooms.set(rooms), error: () => undefined });
  }

  protected createUser(): void {
    if (this.busy()) return;
    this.run(this.admin.createUser(this.newEmail.trim(), this.newName.trim(), this.newPassword), () => {
      this.newName = '';
      this.newEmail = '';
      this.newPassword = '';
    });
  }

  protected startEdit(user: AdminUserView): void {
    this.editingId.set(user.id);
    this.editName = user.displayName;
    this.editPassword = '';
  }

  protected saveEdit(user: AdminUserView): void {
    this.run(this.admin.updateUser(user.id, this.editName.trim() || null, this.editPassword || null), () =>
      this.editingId.set(null),
    );
  }

  protected suspend(user: AdminUserView): void {
    if (!confirm(this.i18n.t('admin.confirmSuspend', user.displayName))) return;
    this.run(this.admin.suspendUser(user.id));
  }

  protected reactivate(user: AdminUserView): void {
    this.run(this.admin.reactivateUser(user.id));
  }

  protected deleteUser(user: AdminUserView): void {
    if (!confirm(this.i18n.t('admin.confirmDeleteUser', user.displayName))) return;
    this.run(this.admin.deleteUser(user.id));
  }

  protected readonly copied = signal<string | null>(null);

  protected copyCode(room: AdminRoomView): void {
    if (!room.joinCode) return;
    this.copyWithFeedback(room.joinCode, `${room.id}:code`);
  }

  /** A shareable link that lands on the home page with the code prefilled. */
  protected copyJoinLink(room: AdminRoomView): void {
    if (!room.joinCode) return;
    this.copyWithFeedback(`${location.origin}/?code=${room.joinCode}`, `${room.id}:link`);
  }

  private copyWithFeedback(text: string, key: string): void {
    void navigator.clipboard.writeText(text).then(() => {
      this.copied.set(key);
      setTimeout(() => this.copied.set(null), 2000);
    });
  }

  protected deleteRoom(room: AdminRoomView): void {
    if (!confirm(this.i18n.t('admin.confirmDeleteRoom', room.title))) return;
    this.run(this.admin.deleteRoom(room.id), () => this.loadRooms());
  }

  /** Shared submit plumbing: busy flag, error surface, list refresh. */
  private run<T>(call: { subscribe: Function }, onSuccess?: () => void): void {
    this.busy.set(true);
    this.error.set(null);
    (call as { subscribe: (o: object) => void }).subscribe({
      next: () => {
        this.busy.set(false);
        onSuccess?.();
        this.loadUsers();
      },
      error: (err: { error?: { message?: string } }) => {
        this.busy.set(false);
        this.error.set(err?.error?.message ?? this.i18n.t('auth.genericError'));
      },
    });
  }
}
