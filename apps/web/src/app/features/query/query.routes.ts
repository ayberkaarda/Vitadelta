import { featureRoutes } from '../../core/i18n/i18n-scope';

export default featureRoutes('query', [
  {
    path: '',
    title: 'query.page_title',
    loadComponent: () => import('./query-page.component').then((m) => m.QueryPageComponent),
  },
]);
