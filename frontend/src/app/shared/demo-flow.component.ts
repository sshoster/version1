import { Component, inject } from '@angular/core';
import { I18nService } from '../core/i18n.service';

/**
 * Silent looping "how it works" animation: four scenes on one 16s CSS timeline —
 * (1) each side shares only what they choose, (2) the AI assistants negotiate,
 * (3) a proposal is drafted, (4) both humans approve. Pure SVG + CSS, no media files;
 * honors prefers-reduced-motion by freezing to a static scene.
 */
@Component({
  selector: 'app-demo-flow',
  template: `
    <div class="card demo">
      <h2>{{ i18n.t('demo.title') }}</h2>
      <svg viewBox="0 0 720 250" role="img" [attr.aria-label]="i18n.t('demo.title')">
        <!-- Center: the shared, transparent table. -->
        <rect x="285" y="70" width="150" height="120" rx="14" class="table" />
        <text x="360" y="60" class="label mid">{{ i18n.t('demo.shared') }}</text>

        <!-- People, always present, with their names. -->
        <text x="80" y="140" class="emoji big">🧑</text>
        <text x="640" y="140" class="emoji big">👩</text>
        <text x="80" y="172" class="name">{{ i18n.t('demo.personA') }}</text>
        <text x="640" y="172" class="name">{{ i18n.t('demo.personB') }}</text>

        <!-- Private notes, locked. -->
        <text x="80" y="200" class="emoji small">🔒</text>
        <text x="640" y="200" class="emoji small">🔒</text>
        <text x="80" y="225" class="label edge">{{ i18n.t('demo.private') }}</text>
        <text x="640" y="225" class="label edge">{{ i18n.t('demo.private') }}</text>

        <!-- Personal AI assistants, gently hovering, each named after their person. -->
        <g class="bob">
          <text x="160" y="65" class="emoji">🤖</text>
          <text x="160" y="95" class="label ai">{{ i18n.t('demo.aiA') }}</text>
        </g>
        <g class="bob bob2">
          <text x="560" y="65" class="emoji">🤖</text>
          <text x="560" y="95" class="label ai">{{ i18n.t('demo.aiB') }}</text>
        </g>

        <!-- Scene 1: chosen messages travel from each person to the shared table. -->
        <text x="110" y="135" class="emoji msg msg-l">💬</text>
        <text x="610" y="135" class="emoji msg msg-r">💬</text>

        <!-- Scene 2: the assistants confer — moving dashes, thinking bubbles, a spark. -->
        <path d="M 175 55 Q 360 -10 545 55" class="talk" fill="none" />
        <text x="195" y="28" class="emoji small s2 think1">💭</text>
        <text x="525" y="28" class="emoji small s2 think2">💭</text>
        <text x="360" y="30" class="emoji s2">✨</text>

        <!-- Scene 3: a proposal document rises from the table. -->
        <g class="s3">
          <text x="360" y="150" class="emoji big doc">📄</text>
        </g>

        <!-- Scene 4: both approve, agreement. -->
        <text x="330" y="120" class="emoji check c1">✅</text>
        <text x="390" y="120" class="emoji check c2">✅</text>
        <text x="360" y="175" class="emoji big s4">🤝</text>
      </svg>

      <div class="captions">
        <p class="cap cap1">{{ i18n.t('demo.cap1') }}</p>
        <p class="cap cap2">{{ i18n.t('demo.cap2') }}</p>
        <p class="cap cap3">{{ i18n.t('demo.cap3') }}</p>
        <p class="cap cap4">{{ i18n.t('demo.cap4') }}</p>
      </div>
    </div>
  `,
  styles: `
    .demo { margin-block-start: var(--space-3); overflow: hidden; }
    .demo h2 { font-size: 1rem; margin: 0 0 var(--space-2); }
    svg { display: block; inline-size: 100%; block-size: auto; }

    .table { fill: var(--color-primary-soft, #e3efe9); stroke: var(--color-primary); stroke-dasharray: 6 5; }
    .emoji { font-size: 26px; text-anchor: middle; dominant-baseline: middle; }
    .emoji.big { font-size: 40px; }
    .emoji.small { font-size: 16px; }
    .label { font-size: 16px; fill: var(--color-text-muted); text-anchor: middle; }
    .label.mid { font-size: 17px; fill: var(--color-primary); font-weight: 700; }
    .label.ai { font-size: 14px; }
    /* Long text centered near the viewBox edges — a notch smaller so it never clips. */
    .label.edge { font-size: 13px; font-weight: 600; }
    .name { font-size: 18px; font-weight: 700; fill: var(--color-text); text-anchor: middle; }

    /* The assistants hover gently, out of phase with each other. */
    .bob { animation: bob 3.2s ease-in-out infinite alternate; }
    .bob2 { animation-delay: 1.6s; }
    @keyframes bob {
      from { transform: translateY(0); }
      to { transform: translateY(-5px); }
    }

    /* One shared 16s timeline; each scene owns a quarter of it. */
    .msg, .s2, .talk, .s3, .check, .s4 { opacity: 0; }

    .msg-l { animation: msg-l 16s ease-in-out infinite; }
    .msg-r { animation: msg-r 16s ease-in-out infinite; }
    @keyframes msg-l {
      0%, 2% { opacity: 0; transform: translateX(0); }
      6% { opacity: 1; }
      18% { opacity: 1; transform: translateX(215px); }
      22%, 100% { opacity: 0; transform: translateX(215px); }
    }
    @keyframes msg-r {
      0%, 4% { opacity: 0; transform: translateX(0); }
      8% { opacity: 1; }
      20% { opacity: 1; transform: translateX(-215px); }
      24%, 100% { opacity: 0; transform: translateX(-215px); }
    }

    .talk { stroke: var(--color-primary); stroke-width: 3; stroke-dasharray: 10 8; animation: talk 16s linear infinite; }
    @keyframes talk {
      0%, 25% { opacity: 0; stroke-dashoffset: 0; }
      28% { opacity: 1; }
      47% { opacity: 1; stroke-dashoffset: -180; }
      50%, 100% { opacity: 0; stroke-dashoffset: -180; }
    }
    .s2 { animation: blink 16s ease-in-out infinite; }
    .think1 { animation-delay: 0.5s; }
    .think2 { animation-delay: 1.3s; }
    @keyframes blink {
      0%, 27% { opacity: 0; }
      32%, 45% { opacity: 1; }
      50%, 100% { opacity: 0; }
    }

    .s3 { animation: rise 16s ease-out infinite; }
    @keyframes rise {
      0%, 50% { opacity: 0; transform: translateY(18px); }
      56%, 72% { opacity: 1; transform: translateY(0); }
      76%, 100% { opacity: 0; transform: translateY(0); }
    }

    .check { animation: pop 16s ease-out infinite; }
    .c2 { animation-delay: 0.8s; }
    @keyframes pop {
      0%, 74% { opacity: 0; transform: scale(0.4); }
      78%, 94% { opacity: 1; transform: scale(1); }
      98%, 100% { opacity: 0; }
    }
    .s4 { transform-origin: 360px 165px; animation: shake 16s ease-in-out infinite; }
    @keyframes shake {
      0%, 79% { opacity: 0; transform: scale(0.5); }
      84% { opacity: 1; transform: scale(1.15); }
      87%, 96% { opacity: 1; transform: scale(1); }
      100% { opacity: 0; }
    }

    /* Captions: one at a time, synced to the same quarters. */
    .captions { position: relative; block-size: 2.4em; }
    .cap {
      position: absolute; inset-inline: 0; margin: 0; text-align: center;
      font-weight: 600; font-size: 1.08rem; color: var(--color-primary); opacity: 0;
      animation: cap 16s ease-in-out infinite;
    }
    .cap2 { animation-delay: 4s; }
    .cap3 { animation-delay: 8s; }
    .cap4 { animation-delay: 12s; }
    @keyframes cap {
      0% { opacity: 0; transform: translateY(6px); }
      3%, 22% { opacity: 1; transform: translateY(0); }
      25%, 100% { opacity: 0; }
    }

    @media (prefers-reduced-motion: reduce) {
      .msg, .talk, .s2, .s3, .check, .s4, .cap, .bob { animation: none; }
      .s3, .cap1 { opacity: 1; }
    }
  `,
})
export class DemoFlowComponent {
  protected readonly i18n = inject(I18nService);
}
