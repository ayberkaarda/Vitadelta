import { featureRoutes } from '../../core/i18n/i18n-scope';

export default featureRoutes('privacy', [
  {
    path: '',
    title: 'privacy.page_title',
    loadComponent: () => import('./privacy-page.component').then((m) => m.PrivacyPageComponent),
  },
]);
