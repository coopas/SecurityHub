import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';

import { Role } from '../models';
import { AuthService } from '../services/auth.service';

/**
 * Autorização por papel a partir de `data.roles`. A verificação real continua
 * no backend; aqui apenas evitamos telas às quais o usuário não tem acesso.
 */
export const roleGuard: CanActivateFn = (route, state): boolean | UrlTree => {
  const authService = inject(AuthService);
  const router = inject(Router);

  if (!authService.isAuthenticated()) {
    return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
  }

  const roles = (route.data['roles'] ?? []) as Role[];
  if (roles.length === 0 || authService.hasRole(...roles)) {
    return true;
  }

  return router.createUrlTree(['/403']);
};
