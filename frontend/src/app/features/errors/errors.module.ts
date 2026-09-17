import { NgModule } from '@angular/core';

import { SharedModule } from '../../shared/shared.module';
import { ForbiddenComponent } from './forbidden/forbidden.component';
import { NotFoundComponent } from './not-found/not-found.component';

/** 403 and 404 pages, referenced directly by the root routing. */
@NgModule({
  declarations: [ForbiddenComponent, NotFoundComponent],
  imports: [SharedModule],
  exports: [ForbiddenComponent, NotFoundComponent],
})
export class ErrorsModule {}
