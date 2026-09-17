import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { roleGuard } from '../../core/guards/role.guard';
import { InviteFormComponent } from './invite-form/invite-form.component';
import { UserDetailComponent } from './user-detail/user-detail.component';
import { UserListComponent } from './user-list/user-list.component';

/**
 * Every route carries the `roleGuard`, the listing included: the whole feature is
 * administrative. `UserService.get`, `update`, `changeRole` and `changeActive` are annotated
 * with `@PreAuthorize("hasRole('ADMIN')")`, and `GET /users` is only more permissive because
 * it serves the vulnerability assignee selector — this screen is not that selector, and
 * opening an administration whose buttons could only fail would help nobody.
 *
 * `convidar` comes before `:id` so it is not read as an identifier.
 *
 * The list is exported so that the role restriction is verifiable by test.
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
