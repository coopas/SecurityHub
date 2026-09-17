import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { AcceptInvitationComponent } from './accept-invitation/accept-invitation.component';
import { ForgotPasswordComponent } from './forgot-password/forgot-password.component';
import { LoginComponent } from './login/login.component';
import { RegisterComponent } from './register/register.component';
import { ResetPasswordComponent } from './reset-password/reset-password.component';

/**
 * All of them inherit the `guestGuard` from the lazy load in `APP_ROUTES`: none of them
 * makes sense for someone who already has a session, and the three new ones create or
 * swap a credential.
 */
export const AUTH_ROUTES: Routes = [
  { path: 'login', component: LoginComponent, title: 'Entrar · SecurityHub' },
  { path: 'register', component: RegisterComponent, title: 'Criar conta · SecurityHub' },
  {
    path: 'forgot-password',
    component: ForgotPasswordComponent,
    title: 'Esqueci minha senha · SecurityHub',
  },
  {
    path: 'reset-password',
    component: ResetPasswordComponent,
    title: 'Definir nova senha · SecurityHub',
  },
  {
    path: 'accept-invitation',
    component: AcceptInvitationComponent,
    title: 'Aceitar convite · SecurityHub',
  },
  { path: '', pathMatch: 'full', redirectTo: 'login' },
];

@NgModule({
  imports: [RouterModule.forChild(AUTH_ROUTES)],
  exports: [RouterModule],
})
export class AuthRoutingModule {}
