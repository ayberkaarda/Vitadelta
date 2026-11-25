import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { EmptyStateComponent } from '../shared/state/empty-state.component';

/** Fallback page for unknown routes. */
@Component({
  selector: 'vd-not-found-page',
  imports: [EmptyStateComponent, RouterLink, TranslocoPipe],
  template: `
    <div class="page narrow">
      <h1 class="sr-only">{{ 'not_found.page_title' | transloco }}</h1>
      <div class="card">
        <vd-empty-state icon="search" [heading]="'not_found.title' | transloco">
          <p>{{ 'not_found.text' | transloco }}</p>
          <div vdStateActions>
            <a class="btn btn-primary" routerLink="/">{{ 'not_found.home' | transloco }}</a>
          </div>
        </vd-empty-state>
      </div>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotFoundPageComponent {}
