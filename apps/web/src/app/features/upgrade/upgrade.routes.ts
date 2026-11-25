import { provideTranslocoScope } from '@jsverse/transloco';

import { featureRoutes } from '../../core/i18n/i18n-scope';

export default featureRoutes('upgrade', [
  {
    path: '',
    title: 'upgrade.page_title',
    // The promo-code form is shared with the account page and keeps its texts in that scope.
    providers: [provideTranslocoScope('upgrade', 'account')],
    loadComponent: () => import('./upgrade-page.component').then((m) => m.UpgradePageComponent),
  },
]);
