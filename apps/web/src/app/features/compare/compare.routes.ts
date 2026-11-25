import { featureRoutes } from '../../core/i18n/i18n-scope';

export default featureRoutes('compare', [
  {
    path: ':baselineId/:candidateId',
    title: 'compare.page_title',
    loadComponent: () => import('./compare-page.component').then((m) => m.ComparePageComponent),
  },
]);
