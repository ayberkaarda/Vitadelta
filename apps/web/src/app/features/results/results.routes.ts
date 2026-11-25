import { featureRoutes } from '../../core/i18n/i18n-scope';

export default featureRoutes('results', [
  {
    path: '',
    title: 'results.page_title',
    loadComponent: () => import('./results-page.component').then((m) => m.ResultsPageComponent),
  },
]);
