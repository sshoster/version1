import { Component, effect, inject, input, signal } from '@angular/core';
import { AvatarService } from '../core/files.service';

const PALETTE = ['#1f6f5c', '#6b4fa1', '#b3612f', '#2f6ab3', '#a13f6b', '#4f7d2f'];

/** Profile picture with a generated-initial fallback (colored by a stable name hash). */
@Component({
  selector: 'app-avatar',
  template: `
    @if (url()) {
      <img class="avatar" [src]="url()" [alt]="name()" [style.inline-size.px]="size()" [style.block-size.px]="size()" />
    } @else {
      <span
        class="avatar initial" aria-hidden="true"
        [style.inline-size.px]="size()" [style.block-size.px]="size()"
        [style.font-size.px]="size() * 0.45" [style.background]="color()"
      >{{ initial() }}</span>
    }
  `,
  styles: `
    .avatar { border-radius: 50%; object-fit: cover; flex-shrink: 0; display: inline-flex; vertical-align: middle; }
    .initial { color: #fff; align-items: center; justify-content: center; font-weight: 700; }
  `,
})
export class AvatarComponent {
  private readonly avatars = inject(AvatarService);

  readonly userId = input.required<string>();
  readonly name = input.required<string>();
  readonly size = input(32);

  protected readonly url = signal<string | null>(null);

  constructor() {
    effect(() => {
      const id = this.userId();
      this.url.set(null);
      void this.avatars.url(id).then((url) => this.url.set(url));
    });
  }

  protected initial(): string {
    return (this.name().trim().charAt(0) || '?').toUpperCase();
  }

  protected color(): string {
    const name = this.name();
    let hash = 0;
    for (let i = 0; i < name.length; i++) hash = (hash * 31 + name.charCodeAt(i)) | 0;
    return PALETTE[Math.abs(hash) % PALETTE.length];
  }
}
