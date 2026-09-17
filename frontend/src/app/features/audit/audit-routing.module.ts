import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { roleGuard } from '../../core/guards/role.guard';
import { AuditListComponent } from './audit-list/audit-list.component';

/**
 * A trilha inteira é restrita a ADMIN, e não apenas alguma ação dentro dela:
 * `AuditQueryService.search` é anotado com `@PreAuthorize("hasRole('ADMIN')")`, então
 * qualquer outro papel receberia 403 já na primeira consulta. O guarda evita abrir uma
 * tela que só poderia falhar; a autorização de verdade continua no servidor.
 *
 * Não há rota de detalhe, edição ou exclusão: o recurso é append-only e não expõe
 * endpoint por linha para nenhum verbo.
 *
 * A lista é exportada, como `APP_ROUTES` já fazia, para que a restrição de papel seja
 * verificável por teste e não apenas visível no código.
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
