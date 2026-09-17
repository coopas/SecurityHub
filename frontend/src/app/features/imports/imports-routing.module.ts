import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { roleGuard } from '../../core/guards/role.guard';
import { ImportListComponent } from './import-list/import-list.component';
import { ImportPreviewComponent } from './import-preview/import-preview.component';
import { ImportUploadComponent } from './import-upload/import-upload.component';

/**
 * `novo` comes before `:id` so it is not read as an identifier, as in assets and
 * vulnerabilities.
 *
 * Only the upload is restricted in the router, to ADMIN and ANALYST — the same licence to
 * create a vulnerability, which is what a confirmed import turns into. The history and the
 * preview stay open: they are read-only, and the actions inside the preview have their own
 * rule, hidden from the roles that could only get a 403. The backend validates again on
 * every call.
 *
 * The list is exported so that the role restriction is verifiable by test.
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
