import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { roleGuard } from '../../core/guards/role.guard';
import { VulnerabilityDetailComponent } from './vulnerability-detail/vulnerability-detail.component';
import { VulnerabilityFormComponent } from './vulnerability-form/vulnerability-form.component';
import { VulnerabilityListComponent } from './vulnerability-list/vulnerability-list.component';

/**
 * `nova` precede `:id` para não ser capturado como identificador. Criar e editar são
 * restritos a ADMIN e ANALYST também no roteador; o backend valida de novo em cada
 * chamada. Status, responsável e comentários vivem no detalhe, aberto a todos os papéis,
 * porque cada ação de lá tem sua própria regra.
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
