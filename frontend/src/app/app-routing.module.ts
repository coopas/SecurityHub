import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { authGuard } from './core/guards/auth.guard';
import { guestGuard } from './core/guards/guest.guard';
import { ForbiddenComponent } from './features/errors/forbidden/forbidden.component';
import { NotFoundComponent } from './features/errors/not-found/not-found.component';
import { MainLayoutComponent } from './layout/main-layout/main-layout.component';

export const APP_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },

  // Authenticated shell: toolbar + sidenav. Every feature comes in as a lazy child.
  {
    path: '',
    component: MainLayoutComponent,
    canActivate: [authGuard],
    children: [
      {
        path: 'dashboard',
        title: 'Dashboard · SecurityHub',
        loadChildren: () =>
          import('./features/dashboard/dashboard.module').then((m) => m.DashboardModule),
      },
      {
        path: 'projects',
        loadChildren: () =>
          import('./features/projects/projects.module').then((m) => m.ProjectsModule),
      },
      {
        path: 'assets',
        loadChildren: () => import('./features/assets/assets.module').then((m) => m.AssetsModule),
      },
      {
        path: 'vulnerabilities',
        loadChildren: () =>
          import('./features/vulnerabilities/vulnerabilities.module').then(
            (m) => m.VulnerabilitiesModule,
          ),
      },
      {
        // The history and the review are read-only, open to any role — it is the backend
        // that restricts uploading, mapping, confirming and discarding. The roleGuard covers
        // only the upload route, in the feature's own router.
        path: 'imports',
        loadChildren: () => import('./features/imports/imports.module').then((m) => m.ImportsModule),
      },
      {
        // The roleGuard with data.roles lives in the feature's own router, next to the
        // component it protects; here the lazy loading is all that is needed.
        path: 'audit',
        loadChildren: () => import('./features/audit/audit.module').then((m) => m.AuditModule),
      },
      {
        // As in 'audit', the roleGuard with data.roles lives in the feature's own
        // router — here on every one of its routes, because user administration is
        // restricted to ADMIN in its entirety.
        path: 'users',
        loadChildren: () => import('./features/users/users.module').then((m) => m.UsersModule),
      },
    ],
  },

  // Public screens (/login and /register): centered card, outside the shell.
  // Declared after the shell so that matching the authenticated routes happens first.
  {
    path: '',
    canActivate: [guestGuard],
    loadChildren: () => import('./features/auth/auth.module').then((m) => m.AuthModule),
  },

  { path: '403', component: ForbiddenComponent, title: 'Acesso negado · SecurityHub' },
  { path: '404', component: NotFoundComponent, title: 'Página não encontrada · SecurityHub' },
  { path: '**', component: NotFoundComponent, title: 'Página não encontrada · SecurityHub' },
];

@NgModule({
  imports: [RouterModule.forRoot(APP_ROUTES, { scrollPositionRestoration: 'enabled' })],
  exports: [RouterModule],
})
export class AppRoutingModule {}
