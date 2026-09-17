import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { roleGuard } from '../../core/guards/role.guard';
import { AssetDetailComponent } from './asset-detail/asset-detail.component';
import { AssetFormComponent } from './asset-form/asset-form.component';
import { AssetListComponent } from './asset-list/asset-list.component';

/**
 * `novo` precede `:id` para não ser capturado como identificador. Escrita é
 * restrita a ADMIN também no roteador; o backend valida de novo em cada chamada.
 */
const routes: Routes = [
  { path: '', component: AssetListComponent, title: 'Ativos · SecurityHub' },
  {
    path: 'novo',
    component: AssetFormComponent,
    canActivate: [roleGuard],
    data: { roles: ['ADMIN'] },
    title: 'Novo ativo · SecurityHub',
  },
  { path: ':id', component: AssetDetailComponent, title: 'Ativo · SecurityHub' },
  {
    path: ':id/editar',
    component: AssetFormComponent,
    canActivate: [roleGuard],
    data: { roles: ['ADMIN'] },
    title: 'Editar ativo · SecurityHub',
  },
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule],
})
export class AssetsRoutingModule {}
