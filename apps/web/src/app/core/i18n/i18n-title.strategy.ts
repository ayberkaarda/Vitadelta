import { Injectable, inject } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { ActivatedRouteSnapshot, RouterStateSnapshot, TitleStrategy } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { BehaviorSubject, combineLatest, forkJoin, map, switchMap } from 'rxjs';

import { I18N_SCOPE_DATA } from './i18n-scope';

interface TitleRequest {
  readonly key: string | null;
  readonly scope: string | null;
}

function scopeOf(snapshot: RouterStateSnapshot): string | null {
  let route: ActivatedRouteSnapshot | null = snapshot.root;
  let scope: string | null = null;
  while (route) {
    const value: unknown = route.data[I18N_SCOPE_DATA];
    if (typeof value === 'string') {
      scope = value;
    }
    route = route.firstChild;
  }
  return scope;
}

/**
 * Treats route `title` values as i18n keys (from the route's feature scope or the root file)
 * and sets the document title to "<page> · <app name>", re-rendered on language changes.
 */
@Injectable({ providedIn: 'root' })
export class I18nTitleStrategy extends TitleStrategy {
  private readonly transloco = inject(TranslocoService);
  private readonly title = inject(Title);
  private readonly request = new BehaviorSubject<TitleRequest>({ key: null, scope: null });

  constructor() {
    super();
    combineLatest([this.request, this.transloco.langChanges$])
      .pipe(
        switchMap(([request, lang]) => {
          const loads = [this.transloco.load(lang)];
          if (request.scope) {
            loads.push(this.transloco.load(`${request.scope}/${lang}`));
          }
          return forkJoin(loads).pipe(
            map(() => {
              const app = this.transloco.translate('app.name', {}, lang);
              if (!request.key) {
                return app;
              }
              const page = this.transloco.translate(request.key, {}, lang);
              return this.transloco.translate('app.title_with_page', { page, app }, lang);
            }),
          );
        }),
      )
      .subscribe((text) => this.title.setTitle(text));
  }

  override updateTitle(snapshot: RouterStateSnapshot): void {
    this.request.next({ key: this.buildTitle(snapshot) ?? null, scope: scopeOf(snapshot) });
  }

  /** Visible for tests: the current request. */
  current(): TitleRequest {
    return this.request.value;
  }
}
