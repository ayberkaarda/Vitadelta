import { authGuard } from '../../core/auth/guards';
import { featureRoutes } from '../../core/i18n/i18n-scope';

export default featureRoutes('account', [
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./account-layout.component').then((m) => m.AccountLayoutComponent),
    children: [
      {
        path: '',
        pathMatch: 'full',
        title: 'account.profile.page_title',
        loadComponent: () => import('./account-page.component').then((m) => m.AccountPageComponent),
      },
      {
        path: 'saved',
        title: 'account.saved.page_title',
        loadComponent: () =>
          import('./saved-products-page.component').then((m) => m.SavedProductsPageComponent),
      },
      {
        path: 'alerts',
        title: 'account.alerts.page_title',
        loadComponent: () => import('./alerts-page.component').then((m) => m.AlertsPageComponent),
      },
      {
        path: 'notifications',
        title: 'account.inbox.page_title',
        loadComponent: () => import('./inbox-page.component').then((m) => m.InboxPageComponent),
      },
    ],
  },
]);
