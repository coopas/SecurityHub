import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { roleGuard } from '../../core/guards/role.guard';
import { VulnerabilityDetailComponent } from './vulnerability-detail/vulnerability-detail.component';
import { VulnerabilityFormComponent } from './vulnerability-form/vulnerability-form.component';
import { VulnerabilityListComponent } from './vulnerability-list/vulnerability-list.component';

/**
 * `nova` comes before `:id` so that it is not captured as an identifier. Creating and
 * editing are restricted to ADMIN and ANALYST in the router too; the backend validates
 * again on every call. Status, assignee and comments live in the detail screen, open to
 * every role, because each action there has a rule of its own.
 */
const routes: Routes = [
  { path: '', component: VulnerabilityListComponent, title: 'Vulnerabilidades · SecurityHub' },
  {
    path: 'nova',
    component: VulnerabilityFormComponent,
    canActivate: [roleGuard],
    data: { roles: ['ADMIN', 'ANALYST'] },
    title: 'Nova vulnerabilidade · SecurityHub',
  },
  { path: ':id', component: VulnerabilityDetailComponent, title: 'Vulnerabilidade · SecurityHub' },
  {
    path: ':id/editar',
    component: VulnerabilityFormComponent,
    canActivate: [roleGuard],
    data: { roles: ['ADMIN', 'ANALYST'] },
    title: 'Editar vulnerabilidade · SecurityHub',
  },
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule],
})
export class VulnerabilitiesRoutingModule {}
