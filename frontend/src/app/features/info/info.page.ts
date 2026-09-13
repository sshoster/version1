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
        <a routerLink="/" class="btn btn-secondary">{{ i18n.t('app.title') }} →</a>
      </div>
    </div>
  `,
})
export class InfoPage {
  protected readonly i18n = inject(I18nService);

  /** Route data: 'about' | 'privacy' | 'contact'. */
  readonly pageKey = input.required<string>();

  protected readonly paragraphs = computed(() =>
    this.i18n
      .t(`page.${this.pageKey()}.body`)
      .split('\n\n')
      .filter((paragraph) => paragraph.trim().length > 0),
  );
}
