import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { roleGuard } from '../../core/guards/role.guard';
import { AssetDetailComponent } from './asset-detail/asset-detail.component';
import { AssetFormComponent } from './asset-form/asset-form.component';
import { AssetListComponent } from './asset-list/asset-list.component';

/**
 * `novo` comes before `:id` so it is not captured as an identifier. Writing is
 * restricted to ADMIN in the router too; the backend validates it again on every call.
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
