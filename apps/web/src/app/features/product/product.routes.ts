import { featureRoutes } from '../../core/i18n/i18n-scope';

export default featureRoutes('product', [
  {
    path: ':id',
    title: 'product.page_title',
    loadComponent: () =>
      import('./product-detail-page.component').then((m) => m.ProductDetailPageComponent),
  },
]);
