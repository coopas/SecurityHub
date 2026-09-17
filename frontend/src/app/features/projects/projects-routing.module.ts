import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { roleGuard } from '../../core/guards/role.guard';
import { ProjectDetailComponent } from './project-detail/project-detail.component';
import { ProjectFormComponent } from './project-form/project-form.component';
import { ProjectListComponent } from './project-list/project-list.component';

/**
 * `novo` precede `:id` para não ser capturado como identificador. Escrita é
 * restrita a ADMIN também no roteador; o backend valida de novo em cada chamada.
 */
const routes: Routes = [
  { path: '', component: ProjectListComponent, title: 'Projetos · SecurityHub' },
  {
    path: 'novo',
    component: ProjectFormComponent,
    canActivate: [roleGuard],
    data: { roles: ['ADMIN'] },
    title: 'Novo projeto · SecurityHub',
  },
  { path: ':id', component: ProjectDetailComponent, title: 'Projeto · SecurityHub' },
  {
    path: ':id/editar',
    component: ProjectFormComponent,
    canActivate: [roleGuard],
    data: { roles: ['ADMIN'] },
    title: 'Editar projeto · SecurityHub',
  },
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule],
})
export class ProjectsRoutingModule {}
