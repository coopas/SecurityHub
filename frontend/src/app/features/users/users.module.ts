import { NgModule } from '@angular/core';

import { SharedModule } from '../../shared/shared.module';
import { InviteFormComponent } from './invite-form/invite-form.component';
import { UserDetailComponent } from './user-detail/user-detail.component';
import { UserListComponent } from './user-list/user-list.component';
import { UsersRoutingModule } from './users-routing.module';

@NgModule({
  declarations: [UserListComponent, InviteFormComponent, UserDetailComponent],
  imports: [SharedModule, UsersRoutingModule],
})
export class UsersModule {}
