import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { roleGuard } from '../../core/guards/role.guard';
import { ImportListComponent } from './import-list/import-list.component';
import { ImportPreviewComponent } from './import-preview/import-preview.component';
import { ImportUploadComponent } from './import-upload/import-upload.component';

/**
 * `novo` precede `:id` para não ser lido como identificador, como em ativos e
 * vulnerabilidades.
 *
 * Só o envio é restrito no roteador, a ADMIN e ANALYST — a mesma licença de criar
 * vulnerabilidade, que é no que uma importação confirmada se transforma. O histórico e a
 * prévia ficam abertos: são leitura, e as ações de dentro da prévia têm a sua própria
 * regra, escondidas dos papéis que só poderiam receber 403. O backend valida de novo em
 * cada chamada.
 *
 * A lista é exportada para que a restrição de papel seja verificável por teste.
 */
export const IMPORTS_ROUTES: Routes = [
  { path: '', component: ImportListComponent, title: 'Importações · SecurityHub' },
  {
    path: 'novo',
    component: ImportUploadComponent,
    canActivate: [roleGuard],
    data: { roles: ['ADMIN', 'ANALYST'] },
    title: 'Nova importação · SecurityHub',
  },
  { path: ':id', component: ImportPreviewComponent, title: 'Prévia da importação · SecurityHub' },
];

@NgModule({
  imports: [RouterModule.forChild(IMPORTS_ROUTES)],
  exports: [RouterModule],
})
export class ImportsRoutingModule {}
