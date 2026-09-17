import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';

import { Role } from '../models';
import { AuthService } from '../services/auth.service';

/**
 * Role-based authorization from `data.roles`. The real check still lives in the
 * backend; here we only keep the user off screens they have no access to.
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
