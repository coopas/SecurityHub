import { NgModule } from '@angular/core';

import { SharedModule } from '../../shared/shared.module';
import { AcceptInvitationComponent } from './accept-invitation/accept-invitation.component';
import { AuthRoutingModule } from './auth-routing.module';
import { ForgotPasswordComponent } from './forgot-password/forgot-password.component';
import { LoginComponent } from './login/login.component';
import { RegisterComponent } from './register/register.component';
import { ResetPasswordComponent } from './reset-password/reset-password.component';

@NgModule({
  declarations: [
    LoginComponent,
    RegisterComponent,
    ForgotPasswordComponent,
    ResetPasswordComponent,
    AcceptInvitationComponent,
  ],
  imports: [SharedModule, AuthRoutingModule],
})
export class AuthModule {}
