import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { authGuard } from './core/guards/auth.guard';
import { guestGuard } from './core/guards/guest.guard';
import { ForbiddenComponent } from './features/errors/forbidden/forbidden.component';
import { NotFoundComponent } from './features/errors/not-found/not-found.component';
import { MainLayoutComponent } from './layout/main-layout/main-layout.component';

export const APP_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },

  // Shell autenticado: toolbar + sidenav. Cada funcionalidade entra como filho lazy.
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
        // O roleGuard com data.roles fica no roteador da própria funcionalidade, junto
        // do componente que ele protege; aqui basta o carregamento sob demanda.
        path: 'audit',
        loadChildren: () => import('./features/audit/audit.module').then((m) => m.AuditModule),
      },
      // Rota da próxima entrega, filha deste mesmo shell:
      // 'users' somente para ADMIN, usando roleGuard com data.roles.
    ],
  },

  // Telas públicas (/login e /register): card centralizado, fora do shell.
  // Declaradas após o shell para que o casamento de rotas autenticadas ocorra primeiro.
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
