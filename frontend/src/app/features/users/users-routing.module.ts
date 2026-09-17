import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { roleGuard } from '../../core/guards/role.guard';
import { InviteFormComponent } from './invite-form/invite-form.component';
import { UserDetailComponent } from './user-detail/user-detail.component';
import { UserListComponent } from './user-list/user-list.component';

/**
 * Toda rota carrega o `roleGuard`, inclusive a listagem: a funcionalidade inteira é
 * administrativa. `UserService.get`, `update`, `changeRole` e `changeActive` são anotados
 * com `@PreAuthorize("hasRole('ADMIN')")`, e `GET /users` só é mais permissivo porque
 * serve ao seletor de responsável das vulnerabilidades — esta tela não é aquele seletor,
 * e abrir uma administração cujos botões só poderiam falhar não ajudaria ninguém.
 *
 * `convidar` precede `:id` para não ser lido como identificador.
 *
 * A lista é exportada para que a restrição de papel seja verificável por teste.
 */
export const USERS_ROUTES: Routes = [
  {
    path: '',
    component: UserListComponent,
    canActivate: [roleGuard],
    data: { roles: ['ADMIN'] },
    title: 'Usuários · SecurityHub',
  },
  {
    path: 'convidar',
    component: InviteFormComponent,
    canActivate: [roleGuard],
    data: { roles: ['ADMIN'] },
    title: 'Convidar usuário · SecurityHub',
  },
  {
    path: ':id',
    component: UserDetailComponent,
    canActivate: [roleGuard],
    data: { roles: ['ADMIN'] },
    title: 'Usuário · SecurityHub',
  },
];

@NgModule({
  imports: [RouterModule.forChild(USERS_ROUTES)],
  exports: [RouterModule],
})
export class UsersRoutingModule {}
