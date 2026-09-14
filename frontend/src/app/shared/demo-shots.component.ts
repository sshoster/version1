import { Component, DestroyRef, inject, signal } from '@angular/core';
import { I18nService } from '../core/i18n.service';

interface Shot {
  src: string;
  captionKey: string;
}

/**
 * "A peek inside": real app screenshots (public/demo/*.png, regenerated with
 * frontend/e2e/seed-demo-shots.mjs + take-demo-shots.mjs) rotating in a browser-style
 * frame. Auto-advances with a cross-fade and pauses on hover; once the viewer picks a
 * specific screenshot (a dot, or a click on the image), the slideshow stops so they can look.
 */
@Component({
  selector: 'app-demo-shots',
  template: `
    <div class="card shots">
      <h2>{{ i18n.t('demo.peek') }}</h2>
      <div class="frame" (mouseenter)="paused = true" (mouseleave)="paused = false" (click)="manual = true">
        <div class="chrome" aria-hidden="true"><span></span><span></span><span></span></div>
        @for (shot of shots; track shot.src; let i = $index) {
          <img
            [src]="shot.src" [alt]="i18n.t(shot.captionKey)"
            [class.active]="i === index()" loading="lazy" draggable="false"
          />
        }
      </div>
      <p class="caption">{{ i18n.t(shots[index()].captionKey) }}</p>
      <div class="dots" role="tablist">
        @for (shot of shots; track shot.src; let i = $index) {
          <button
            type="button" class="dot" [class.on]="i === index()"
            [attr.aria-label]="i18n.t(shot.captionKey)" (click)="go(i)"
          ></button>
        }
      </div>
    </div>
  `,
  styles: `
    .shots h2 { font-size: 1rem; margin: 0 0 var(--space-2); }
    .frame {
      position: relative; border: 1px solid var(--color-border); border-radius: 10px;
      overflow: hidden; background: var(--color-bg);
      aspect-ratio: 1280 / 832; /* image ratio + chrome bar */
    }
    .chrome {
      display: flex; gap: 5px; padding: 8px 10px;
      background: var(--color-bg); border-block-end: 1px solid var(--color-border);
    }
    .chrome span { inline-size: 9px; block-size: 9px; border-radius: 50%; background: var(--color-border); }
    .frame img {
      position: absolute; inset-block-start: 26px; inset-inline: 0;
      inline-size: 100%; display: block;
      opacity: 0; transition: opacity 0.7s ease;
    }
    .frame img.active { opacity: 1; }
    .caption { text-align: center; font-weight: 600; color: var(--color-primary); margin: var(--space-2) 0 0; min-block-size: 1.4em; }
    .dots { display: flex; justify-content: center; gap: var(--space-2); margin-block-start: var(--space-1); }
    .dot {
      inline-size: 10px; block-size: 10px; border-radius: 50%; border: none; padding: 0;
      background: var(--color-border); cursor: pointer;
    }
    .dot.on { background: var(--color-primary); }
  `,
})
export class DemoShotsComponent {
  protected readonly i18n = inject(I18nService);

  protected readonly shots: Shot[] = [
    { src: 'demo/home.png', captionKey: 'demo.shot.home' },
    { src: 'demo/room.png', captionKey: 'demo.shot.room' },
    { src: 'demo/assistant.png', captionKey: 'demo.shot.assistant' },
  ];

  protected readonly index = signal(0);
  protected paused = false;
  /** Set once the viewer picks a screenshot themselves — the slideshow stops for them. */
  protected manual = false;

  constructor() {
    const timer = setInterval(() => {
      if (!this.paused && !this.manual) this.index.update((current) => (current + 1) % this.shots.length);
    }, 5000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected go(i: number): void {
    this.manual = true;
    this.index.set(i);
  }
}
