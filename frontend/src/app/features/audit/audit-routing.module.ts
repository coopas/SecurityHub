import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { roleGuard } from '../../core/guards/role.guard';
import { AuditListComponent } from './audit-list/audit-list.component';

/**
 * The whole trail is restricted to ADMIN, and not just some action inside it:
 * `AuditQueryService.search` is annotated with `@PreAuthorize("hasRole('ADMIN')")`, so
 * any other role would get a 403 on the very first query. The guard avoids opening a
 * screen that could only fail; the real authorization stays on the server.
 *
 * There is no detail, edit or delete route: the resource is append-only and exposes no
 * per-row endpoint for any verb.
 *
 * The list is exported, as `APP_ROUTES` already did, so that the role restriction is
 * verifiable by test and not merely visible in the code.
 */
export const AUDIT_ROUTES: Routes = [
  {
    path: '',
    component: AuditListComponent,
    canActivate: [roleGuard],
    data: { roles: ['ADMIN'] },
    title: 'Auditoria · SecurityHub',
  },
];

@NgModule({
  imports: [RouterModule.forChild(AUDIT_ROUTES)],
  exports: [RouterModule],
})
export class AuditRoutingModule {}
