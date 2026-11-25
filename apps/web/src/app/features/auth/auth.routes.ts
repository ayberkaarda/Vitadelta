import { featureRoutes } from '../../core/i18n/i18n-scope';

export default featureRoutes('auth', [
  { path: '', pathMatch: 'full', redirectTo: 'login' },
  {
    path: 'register',
    title: 'auth.register.page_title',
    loadComponent: () =>
      import('./auth-register-page.component').then((m) => m.AuthRegisterPageComponent),
  },
  {
    path: 'verify',
    title: 'auth.verify.page_title',
    loadComponent: () =>
      import('./auth-verify-page.component').then((m) => m.AuthVerifyPageComponent),
  },
  {
    path: 'login',
    title: 'auth.login.page_title',
    loadComponent: () =>
      import('./auth-login-page.component').then((m) => m.AuthLoginPageComponent),
  },
  {
    path: 'forgot',
    title: 'auth.forgot.page_title',
    loadComponent: () =>
      import('./auth-forgot-page.component').then((m) => m.AuthForgotPageComponent),
  },
  {
    path: 'reset',
    title: 'auth.reset.page_title',
    loadComponent: () =>
      import('./auth-reset-page.component').then((m) => m.AuthResetPageComponent),
  },
]);
