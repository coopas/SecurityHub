import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { roleGuard } from '../../core/guards/role.guard';
import { ProjectDetailComponent } from './project-detail/project-detail.component';
import { ProjectFormComponent } from './project-form/project-form.component';
import { ProjectListComponent } from './project-list/project-list.component';

/**
 * `novo` comes before `:id` so it is not captured as an identifier. Writing is
 * restricted to ADMIN in the router too; the backend validates it again on every call.
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
