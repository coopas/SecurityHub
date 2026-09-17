import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { AcceptInvitationComponent } from './accept-invitation/accept-invitation.component';
import { ForgotPasswordComponent } from './forgot-password/forgot-password.component';
import { LoginComponent } from './login/login.component';
import { RegisterComponent } from './register/register.component';
import { ResetPasswordComponent } from './reset-password/reset-password.component';

/**
 * Todas herdam o `guestGuard` do carregamento lazy em `APP_ROUTES`: nenhuma delas faz
 * sentido para quem já tem sessão, e as três novas criam ou trocam credencial.
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
