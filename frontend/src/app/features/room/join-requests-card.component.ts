import { Component, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { I18nService } from '../../core/i18n.service';
import { JoinRequestView, ParticipantRole } from '../../core/models';
import { RoomsService } from '../../core/rooms.service';

/** Admin view: pending join-by-code requests with approve (role choice) / reject. */
@Component({
  selector: 'app-join-requests-card',
  imports: [FormsModule],
  template: `
    @if (requests().length > 0) {
      <div class="card stack panel">
        <h2>⏳ {{ i18n.t('join.requestsTitle') }}</h2>
        @for (request of requests(); track request.id) {
          <div class="request">
            <strong>{{ request.displayName }}</strong>
            <div class="decide-row">
              <label class="role-label">
                {{ i18n.t('join.asRole') }}
                <select name="role-{{ request.id }}" [(ngModel)]="roles[request.id]">
                  <option value="PARTY">{{ i18n.t('room.role.PARTY') }}</option>
                  <option value="ADVISOR">{{ i18n.t('room.role.ADVISOR') }}</option>
                  <option value="OBSERVER">{{ i18n.t('room.role.OBSERVER') }}</option>
                </select>
              </label>
              <button class="btn btn-primary" type="button" [disabled]="busy()" (click)="approve(request)">
                {{ i18n.t('join.approve') }}
              </button>
              <button class="btn btn-secondary" type="button" [disabled]="busy()" (click)="reject(request)">
                {{ i18n.t('join.reject') }}
              </button>
            </div>
          </div>
        }
      </div>
    }
  `,
  styles: `
    .panel h2 { font-size: 1rem; margin: 0; }
    .request { border: 1px solid var(--color-border); border-radius: var(--radius); padding: var(--space-2) var(--space-3); }
    .decide-row { display: flex; gap: var(--space-1); flex-wrap: wrap; align-items: center; margin-block-start: var(--space-2); }
    .role-label { display: flex; gap: 4px; align-items: center; font-size: 0.85rem; }
    .decide-row .btn { min-height: 40px; padding: 0 var(--space-3); }
  `,
})
export class JoinRequestsCardComponent {
  protected readonly i18n = inject(I18nService);
  private readonly rooms = inject(RoomsService);

  readonly roomId = input.required<string>();

  protected readonly requests = signal<JoinRequestView[]>([]);
  protected readonly busy = signal(false);
  protected roles: Record<string, ParticipantRole> = {};

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.rooms.joinRequests(this.roomId()).subscribe({
      next: (requests) => {
        this.requests.set(requests);
        requests.forEach((request) => {
          if (!this.roles[request.id]) this.roles[request.id] = 'PARTY';
        });
      },
      error: () => undefined,
    });
  }

  protected approve(request: JoinRequestView): void {
    this.busy.set(true);
    this.rooms.approveJoin(this.roomId(), request.id, this.roles[request.id] ?? 'PARTY').subscribe({
      next: () => {
        this.busy.set(false);
        this.refresh();
      },
      error: () => {
        this.busy.set(false);
        this.refresh();
      },
    });
  }

  protected reject(request: JoinRequestView): void {
    this.busy.set(true);
    this.rooms.rejectJoin(this.roomId(), request.id).subscribe({
      next: () => {
        this.busy.set(false);
        this.refresh();
      },
      error: () => {
        this.busy.set(false);
        this.refresh();
      },
    });
  }
}
