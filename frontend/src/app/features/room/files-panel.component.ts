import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { FileView, FilesService } from '../../core/files.service';
import { I18nService } from '../../core/i18n.service';
import { ParticipantResponse, VisibilityScope } from '../../core/models';
import { AvatarComponent } from '../../shared/avatar.component';

/**
 * All the discussion's files in one place — the caller's private files and everything shared with
 * them — following the message trust model: private by default, explicit audience on share.
 */
@Component({
  selector: 'app-files-panel',
  imports: [FormsModule, DatePipe, DecimalPipe, AvatarComponent],
  template: `
    <div class="card stack">
      <div class="head-row">
        <h2>{{ i18n.t('files.title') }}</h2>
        @if (canUpload()) {
          <label class="btn btn-secondary upload-btn">
            {{ busy() ? i18n.t('common.loading') : i18n.t('files.upload') }}
            <input type="file" hidden (change)="onFilePicked($event)"
                   accept="image/png,image/jpeg,image/webp,image/gif,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.csv" />
          </label>
        }
      </div>
      <p class="muted small">{{ i18n.t('files.uploadHint') }}</p>
      @if (error()) {
        <div class="error-box" role="alert">{{ error() }}</div>
      }

      @if (files().length === 0) {
        <p class="muted small">{{ i18n.t('files.empty') }}</p>
      }

      @if (myFiles().length > 0) {
        <h3>{{ i18n.t('files.mine') }}</h3>
        @for (file of myFiles(); track file.id) {
          <div class="file-row" [class.withdrawn]="file.status === 'WITHDRAWN'">
            <span class="icon">{{ file.kind === 'IMAGE' ? '🖼️' : '📄' }}</span>
            <div class="meta">
              <strong>{{ file.filename }}</strong>
              <span class="muted small">
                {{ file.sizeBytes / 1024 / 1024 | number: '1.0-2' }}MB · {{ file.createdAt | date: 'short' }} ·
                @switch (file.status) {
                  @case ('PRIVATE') { 🔒 {{ i18n.t('files.private') }} }
                  @case ('SHARED') { 👥 {{ i18n.t('scope.' + file.scope) }} }
                  @case ('WITHDRAWN') { {{ i18n.t('files.withdrawn') }} }
                }
              </span>
            </div>
            <div class="row-actions">
              <button class="btn btn-quiet" type="button" (click)="download(file)">{{ i18n.t('files.download') }}</button>
              @if (file.status === 'PRIVATE') {
                <button class="btn btn-secondary" type="button" (click)="sharingId.set(sharingId() === file.id ? null : file.id)">
                  {{ i18n.t('files.share') }}
                </button>
                <button class="btn btn-quiet" type="button" (click)="deletePrivate(file)">{{ i18n.t('files.delete') }}</button>
              } @else if (file.status === 'SHARED') {
                <button class="btn btn-quiet" type="button" (click)="withdraw(file)">{{ i18n.t('files.withdraw') }}</button>
              }
            </div>
            @if (sharingId() === file.id) {
              <div class="share-box">
                <label for="file-scope">{{ i18n.t('share.whoSees') }}</label>
                <select id="file-scope" name="fileScope" [(ngModel)]="shareScope">
                  <option value="ALL_PARTIES">{{ i18n.t('scope.ALL_PARTIES') }}</option>
                  <option value="ALL_ROOM_PARTICIPANTS">{{ i18n.t('scope.ALL_ROOM_PARTICIPANTS') }}</option>
                  <option value="MY_ADVISORS">{{ i18n.t('scope.MY_ADVISORS') }}</option>
                  <option value="SELECTED_PARTICIPANTS">{{ i18n.t('scope.SELECTED_PARTICIPANTS') }}</option>
                </select>
                @if (shareScope === 'SELECTED_PARTICIPANTS') {
                  @for (participant of participants(); track participant.id) {
                    <label class="recipient-option">
                      <input type="checkbox" [checked]="selected().has(participant.id)" (change)="toggle(participant.id)" />
                      {{ participant.displayName }}
                    </label>
                  }
                }
                <p class="muted small">{{ i18n.t('files.deleteNote') }}</p>
                <button class="btn btn-primary" type="button" [disabled]="busy()" (click)="confirmShare(file)">
                  {{ i18n.t('files.confirmShare') }}
                </button>
              </div>
            }
          </div>
        }
      }

      @if (sharedWithMe().length > 0) {
        <h3>{{ i18n.t('files.shared') }}</h3>
        @for (file of sharedWithMe(); track file.id) {
          <div class="file-row">
            <app-avatar [userId]="file.ownerUserId" [name]="file.ownerDisplayName" [size]="28" />
            <div class="meta">
              <strong>{{ file.filename }}</strong>
              <span class="muted small">
                {{ i18n.t('files.sharedBy', file.ownerDisplayName) }} ·
                {{ file.sizeBytes / 1024 / 1024 | number: '1.0-2' }}MB · {{ file.sharedAt | date: 'short' }}
              </span>
            </div>
            <button class="btn btn-quiet" type="button" (click)="download(file)">{{ i18n.t('files.download') }}</button>
          </div>
        }
      }
    </div>
  `,
  styles: `
    .head-row { display: flex; align-items: center; justify-content: space-between; gap: var(--space-2); }
    .head-row h2 { margin: 0; }
    h3 { font-size: 0.95rem; margin: var(--space-2) 0 0; }
    .small { font-size: 0.82rem; }
    .file-row {
      display: flex; align-items: center; gap: var(--space-2); flex-wrap: wrap;
      border: 1px solid var(--color-border); border-radius: var(--radius); padding: var(--space-2) var(--space-3);
    }
    .file-row.withdrawn { opacity: 0.65; }
    .icon { font-size: 1.3rem; }
    .meta { flex: 1; min-width: 160px; display: flex; flex-direction: column; }
    .row-actions { display: flex; gap: var(--space-1); flex-wrap: wrap; }
    .share-box {
      flex-basis: 100%; background: var(--color-bg); border-radius: var(--radius);
      padding: var(--space-3); display: flex; flex-direction: column; gap: var(--space-2);
    }
    .recipient-option { display: flex; gap: var(--space-2); align-items: center; min-height: 36px; }
    .upload-btn { cursor: pointer; }
  `,
})
export class FilesPanelComponent {
  protected readonly i18n = inject(I18nService);
  private readonly filesService = inject(FilesService);

  readonly roomId = input.required<string>();
  readonly canUpload = input.required<boolean>();
  readonly participants = input.required<ParticipantResponse[]>();

  protected readonly files = signal<FileView[]>([]);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly sharingId = signal<string | null>(null);
  protected readonly selected = signal<Set<string>>(new Set());
  protected shareScope: VisibilityScope = 'ALL_PARTIES';

  protected readonly myFiles = computed(() => this.files().filter((file) => file.mine));
  protected readonly sharedWithMe = computed(() => this.files().filter((file) => !file.mine));

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.filesService.list(this.roomId()).subscribe({
      next: (files) => this.files.set(files),
      error: () => undefined,
    });
  }

  protected onFilePicked(event: Event): void {
    const inputElement = event.target as HTMLInputElement;
    const file = inputElement.files?.[0];
    inputElement.value = '';
    if (!file) return;
    this.error.set(null);
    if (file.size > 50 * 1024 * 1024) {
      this.error.set(this.i18n.t('files.tooBig'));
      return;
    }
    this.busy.set(true);
    this.filesService.upload(this.roomId(), file).subscribe({
      next: () => {
        this.busy.set(false);
        this.refresh();
      },
      error: (err: { error?: { message?: string } }) => {
        this.busy.set(false);
        this.error.set(err?.error?.message ?? this.i18n.t('files.badType'));
      },
    });
  }

  protected toggle(participantId: string): void {
    this.selected.update((current) => {
      const next = new Set(current);
      if (next.has(participantId)) next.delete(participantId);
      else next.add(participantId);
      return next;
    });
  }

  protected confirmShare(file: FileView): void {
    this.busy.set(true);
    this.error.set(null);
    const recipients = this.shareScope === 'SELECTED_PARTICIPANTS' ? [...this.selected()] : undefined;
    this.filesService.share(this.roomId(), file.id, this.shareScope, recipients).subscribe({
      next: () => {
        this.busy.set(false);
        this.sharingId.set(null);
        this.selected.set(new Set());
        this.refresh();
      },
      error: (err: { error?: { message?: string } }) => {
        this.busy.set(false);
        this.error.set(err?.error?.message ?? this.i18n.t('auth.genericError'));
      },
    });
  }

  protected withdraw(file: FileView): void {
    this.filesService.withdraw(this.roomId(), file.id).subscribe({ next: () => this.refresh() });
  }

  protected deletePrivate(file: FileView): void {
    this.filesService.deletePrivate(this.roomId(), file.id).subscribe({ next: () => this.refresh() });
  }

  protected download(file: FileView): void {
    void this.filesService.contentUrl(this.roomId(), file.id).then((url) => {
      if (!url) return;
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = file.filename;
      anchor.click();
      setTimeout(() => URL.revokeObjectURL(url), 30_000);
    });
  }
}
