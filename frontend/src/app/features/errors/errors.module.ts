import { NgModule } from '@angular/core';

import { SharedModule } from '../../shared/shared.module';
import { ForbiddenComponent } from './forbidden/forbidden.component';
import { NotFoundComponent } from './not-found/not-found.component';

/** Páginas 403 e 404, referenciadas diretamente pelo roteamento raiz. */
@NgModule({
  declarations: [ForbiddenComponent, NotFoundComponent],
  imports: [SharedModule],
  exports: [ForbiddenComponent, NotFoundComponent],
})
export class ErrorsModule {}
