import { NgModule } from '@angular/core';

import { SharedModule } from '../shared/shared.module';
import { MainLayoutComponent } from './main-layout/main-layout.component';

/** Shell autenticado (toolbar + sidenav), importado pelo AppModule. */
@NgModule({
  declarations: [MainLayoutComponent],
  imports: [SharedModule],
  exports: [MainLayoutComponent],
})
export class LayoutModule {}
