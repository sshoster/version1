import { Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { I18nService } from '../../core/i18n.service';

/** Static info pages (About / Privacy / Contact) driven by route data + i18n. */
@Component({
  selector: 'app-info-page',
  imports: [RouterLink],
  template: `
    <div class="page">
      <div class="card stack">
        <h1>{{ i18n.t('page.' + pageKey() + '.title') }}</h1>
        @for (paragraph of paragraphs(); track $index) {
          <p>{{ paragraph }}</p>
        }

        @if (pageKey() === 'about') {
          <div class="uses">
            @for (use of useCases; track use; let i = $index) {
              <span class="use-chip" [style.animation-delay]="i * 120 + 'ms'">{{ i18n.t('page.about.uses.' + use) }}</span>
            }
          </div>

          <figure class="mock">
            <div class="mock-window">
              <div class="mock-bar">
                <span class="dot"></span><span class="dot"></span><span class="dot"></span>
                <span class="mock-title">{{ i18n.t('page.about.mock.title') }}</span>
              </div>
              <div class="mock-body">
                <div class="typing" aria-hidden="true"><span></span><span></span><span></span></div>
                <div class="bubble theirs">{{ i18n.t('page.about.mock.msg1') }}</div>
                <div class="bubble assistant">{{ i18n.t('page.about.mock.msg2') }}</div>
                <div class="bubble mine">
                  {{ i18n.t('page.about.mock.msg3') }}
                  <span class="badge">{{ i18n.t('page.about.mock.badge') }}</span>
                </div>
              </div>
            </div>
            <figcaption>{{ i18n.t('page.about.mock.caption') }}</figcaption>
          </figure>
        }

        @if (pageKey() === 'contact') {
          <ul class="contact-list">
            <li>
              <span class="contact-icon" aria-hidden="true">👤</span>
              <span class="contact-label">{{ i18n.t('page.contact.nameLabel') }}</span>
              <span class="contact-value">{{ i18n.t('page.contact.name') }}</span>
            </li>
            <li>
              <span class="contact-icon" aria-hidden="true">✉️</span>
              <span class="contact-label">{{ i18n.t('page.contact.emailLabel') }}</span>
              <a class="contact-value" href="mailto:{{ email }}">{{ email }}</a>
            </li>
            <li>
              <span class="contact-icon" aria-hidden="true">📞</span>
              <span class="contact-label">{{ i18n.t('page.contact.phoneLabel') }}</span>
              <a class="contact-value" dir="ltr" href="tel:{{ phone }}">{{ phoneDisplay }}</a>
            </li>
          </ul>
        }

        <a routerLink="/" class="btn btn-secondary">{{ i18n.t('app.title') }} →</a>
      </div>
    </div>
  `,
  styles: `
    /* --- About: animated use-case chips --- */
    .uses {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2);
      margin-block-start: var(--space-2);
    }

    .use-chip {
      padding: 6px 14px;
      border-radius: 999px;
      border: 1px solid var(--color-border);
      background: color-mix(in srgb, var(--color-primary) 6%, var(--color-bg));
      font-size: 0.85rem;
      white-space: nowrap;
      opacity: 0;
      animation: chip-in 0.5s ease-out forwards;
      transition: transform 0.15s ease, box-shadow 0.15s ease;
    }

    .use-chip:hover {
      transform: translateY(-2px);
      box-shadow: 0 4px 12px rgb(0 0 0 / 0.1);
    }

    @keyframes chip-in {
      from { opacity: 0; transform: translateY(10px) scale(0.9); }
      to { opacity: 1; transform: none; }
    }

    /* --- About: animated app "screenshot" — a looping chat scene --- */
    .mock { margin: var(--space-4) 0 0; }

    .mock-window {
      border: 1px solid var(--color-border);
      border-radius: 12px;
      overflow: hidden;
      background: var(--color-bg);
      box-shadow: 0 10px 30px rgb(0 0 0 / 0.12);
      max-inline-size: 560px;
      animation: window-float 6s ease-in-out infinite;
    }

    @keyframes window-float {
      0%, 100% { transform: translateY(0); }
      50% { transform: translateY(-6px); }
    }

    .mock-bar {
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 8px 12px;
      background: var(--color-surface, #f4f6f5);
      border-block-end: 1px solid var(--color-border);
    }

    .mock-bar .dot {
      inline-size: 10px;
      block-size: 10px;
      border-radius: 50%;
      background: var(--color-border);
    }
    .mock-bar .dot:first-child { background: #e8695a; }
    .mock-bar .dot:nth-child(2) { background: #f0b429; }
    .mock-bar .dot:nth-child(3) { background: #5cb85c; }

    .mock-title {
      margin-inline-start: var(--space-2);
      font-size: 0.8rem;
      color: var(--color-text-muted, #6b7570);
    }

    .mock-body {
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
      padding: var(--space-3);
      position: relative;
    }

    /* Typing indicator: three bouncing dots, shown at the start of each loop. */
    .typing {
      align-self: flex-start;
      display: flex;
      gap: 4px;
      padding: 10px 14px;
      border-radius: 14px;
      border-start-start-radius: 4px;
      background: var(--color-surface, #f4f6f5);
      border: 1px solid var(--color-border);
      animation: typing-slot 12s linear infinite;
      position: absolute;
      inset-block-start: var(--space-3);
    }

    .typing span {
      inline-size: 6px;
      block-size: 6px;
      border-radius: 50%;
      background: var(--color-text-muted, #6b7570);
      animation: typing-bounce 1s ease-in-out infinite;
    }
    .typing span:nth-child(2) { animation-delay: 0.15s; }
    .typing span:nth-child(3) { animation-delay: 0.3s; }

    @keyframes typing-slot {
      0%, 9% { opacity: 1; }
      12%, 100% { opacity: 0; }
    }

    @keyframes typing-bounce {
      0%, 100% { transform: translateY(0); }
      40% { transform: translateY(-4px); }
    }

    .bubble {
      max-inline-size: 85%;
      padding: 8px 12px;
      border-radius: 14px;
      font-size: 0.85rem;
      line-height: 1.45;
      opacity: 0;
      animation: 12s ease-in-out infinite;
    }

    .bubble.theirs {
      align-self: flex-start;
      background: var(--color-surface, #f4f6f5);
      border: 1px solid var(--color-border);
      border-start-start-radius: 4px;
      animation-name: msg-1;
    }

    .bubble.assistant {
      align-self: center;
      background: color-mix(in srgb, var(--color-primary) 8%, var(--color-bg));
      border: 1px dashed var(--color-primary);
      color: var(--color-text);
      font-style: italic;
      animation-name: msg-2;
    }

    .bubble.mine {
      align-self: flex-end;
      background: var(--color-primary);
      color: #fff;
      border-start-end-radius: 4px;
      animation-name: msg-3;
    }

    /* Each message pops in at its moment in the 12s loop, then everything resets. */
    @keyframes msg-1 {
      0%, 11% { opacity: 0; transform: translateY(10px) scale(0.95); }
      15%, 93% { opacity: 1; transform: none; }
      97%, 100% { opacity: 0; transform: none; }
    }

    @keyframes msg-2 {
      0%, 39% { opacity: 0; transform: translateY(10px) scale(0.95); }
      43%, 93% { opacity: 1; transform: none; }
      97%, 100% { opacity: 0; transform: none; }
    }

    @keyframes msg-3 {
      0%, 65% { opacity: 0; transform: translateY(10px) scale(0.95); }
      69%, 93% { opacity: 1; transform: none; }
      97%, 100% { opacity: 0; transform: none; }
    }

    .badge {
      display: block;
      margin-block-start: 6px;
      font-size: 0.68rem;
      opacity: 0;
      animation: badge-in 12s ease-in-out infinite;
    }

    @keyframes badge-in {
      0%, 80% { opacity: 0; }
      85%, 93% { opacity: 0.9; }
      97%, 100% { opacity: 0; }
    }

    /* Accessibility: no motion — show the finished scene statically. */
    @media (prefers-reduced-motion: reduce) {
      .use-chip, .mock-window, .bubble { animation: none; opacity: 1; }
      .badge { animation: none; opacity: 0.85; }
      .typing { display: none; }
    }

    .mock figcaption {
      margin-block-start: var(--space-2);
      font-size: 0.8rem;
      color: var(--color-text-muted, #6b7570);
    }

    /* --- Contact --- */
    .contact-list {
      list-style: none;
      margin: var(--space-3) 0 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
      max-inline-size: 420px;
    }

    .contact-list li {
      display: flex;
      align-items: center;
      gap: var(--space-2);
      padding: 10px 14px;
      border: 1px solid var(--color-border);
      border-radius: 10px;
      background: var(--color-surface, #f4f6f5);
    }

    .contact-icon { flex: none; font-size: 1.1rem; }
    .contact-label { flex: none; min-inline-size: 60px; color: var(--color-text-muted, #6b7570); font-size: 0.85rem; }
    .contact-value { font-weight: 600; color: var(--color-text); text-decoration: none; overflow-wrap: anywhere; }
    a.contact-value:hover { color: var(--color-primary); text-decoration: underline; }
  `,
})
export class InfoPage {
  protected readonly i18n = inject(I18nService);

  /** Route data: 'about' | 'privacy' | 'contact'. */
  readonly pageKey = input.required<string>();

  protected readonly useCases = ['brainstorm', 'trip', 'house', 'car', 'vendor', 'home'];

  protected readonly email = 'sshoster@gmail.com';
  protected readonly phone = '+972549989994';
  protected readonly phoneDisplay = '+972-54-998-9994';

  protected readonly paragraphs = computed(() =>
    this.i18n
      .t(`page.${this.pageKey()}.body`)
      .split('\n\n')
      .filter((paragraph) => paragraph.trim().length > 0),
  );
}
