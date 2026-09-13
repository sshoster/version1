import { Component, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { I18nService } from '../../core/i18n.service';
import { InvitationCreated, ParticipantRole } from '../../core/models';
import { RoomsService } from '../../core/rooms.service';

/** Owner's "add people" card — lives in the static side column, outside the chat scroll. */
@Component({
  selector: 'app-invite-card',
  imports: [FormsModule],
  template: `
    <div class="card stack panel">
      <h2>{{ i18n.t('room.invite') }}</h2>
      @if (!invitation()) {
        <div class="field">
          <label for="role">{{ i18n.t('room.invite.role') }}</label>
          <select id="role" name="role" [(ngModel)]="inviteRole">
            <option value="PARTY">{{ i18n.t('room.role.PARTY') }}</option>
            <option value="ADVISOR">{{ i18n.t('room.role.ADVISOR') }}</option>
            <option value="OBSERVER">{{ i18n.t('room.role.OBSERVER') }}</option>
          </select>
        </div>
        <div class="field">
          <label for="inviteFirstName">{{ i18n.t('invite.firstName') }}</label>
          <input id="inviteFirstName" name="inviteFirstName" type="text" [(ngModel)]="firstName" />
        </div>
        <div class="field">
          <label for="inviteLastName">{{ i18n.t('invite.lastName') }}</label>
          <input id="inviteLastName" name="inviteLastName" type="text" [(ngModel)]="lastName" />
        </div>
        <div class="field">
          <label for="inviteEmail">{{ i18n.t('room.invite.email') }}</label>
          <input id="inviteEmail" name="inviteEmail" type="email" dir="ltr" [(ngModel)]="email" />
          <span class="hint">{{ i18n.t('room.invite.emailHint') }}</span>
        </div>
        <button class="btn btn-primary" type="button" [disabled]="busy() || !firstName.trim()" (click)="invite()">
          {{ i18n.t('room.invite.create') }}
        </button>
        <p class="muted small">{{ i18n.t('room.invite.explain') }}</p>
      } @else {
        @if (invitation()!.email && invitation()!.emailSent) {
          <p><span class="badge">{{ i18n.t('room.invite.emailSentTo', invitation()!.email!) }}</span></p>
        } @else if (invitation()!.email && !invitation()!.emailSent) {
          <div class="error-box">{{ i18n.t('room.invite.emailNotSent') }}</div>
        }
        <p class="muted small">{{ i18n.t('room.invite.shareHint') }}</p>
        <div class="invite-link" dir="ltr">{{ invitation()!.acceptUrl }}</div>
        <button class="btn btn-secondary" type="button" (click)="copyLink()">
          {{ copied() ? i18n.t('room.invite.copied') : i18n.t('room.invite.copy') }}
        </button>
        <button class="btn btn-quiet" type="button" (click)="invitation.set(null)">
          {{ i18n.t('room.invite') }} +
        </button>
      }
    </div>
  `,
  styles: `
    .panel h2 { font-size: 1rem; margin-block-end: var(--space-1); }
    .small { font-size: 0.8rem; }
    .invite-link {
      background: var(--color-bg); border: 1px dashed var(--color-border); border-radius: var(--radius);
      padding: var(--space-2); overflow-wrap: anywhere; font-size: 0.75rem;
    }
  `,
})
export class InviteCardComponent {
  protected readonly i18n = inject(I18nService);
  private readonly rooms = inject(RoomsService);

  readonly roomId = input.required<string>();
  readonly invited = output<void>();

  protected readonly invitation = signal<InvitationCreated | null>(null);
  protected readonly busy = signal(false);
  protected readonly copied = signal(false);

  protected inviteRole: ParticipantRole = 'PARTY';
  protected firstName = '';
  protected lastName = '';
  protected email = '';

  protected invite(): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.rooms
      .invite(this.roomId(), this.inviteRole, this.email.trim() || null, this.firstName.trim() || null, this.lastName.trim() || null)
      .subscribe({
        next: (created) => {
          this.invitation.set(created);
          this.busy.set(false);
          this.firstName = '';
          this.lastName = '';
          this.email = '';
          this.invited.emit();
        },
        error: () => this.busy.set(false),
      });
  }

  protected copyLink(): void {
    const url = this.invitation()?.acceptUrl;
    if (!url) return;
    void navigator.clipboard.writeText(url).then(() => {
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 2500);
    });
  }
}
