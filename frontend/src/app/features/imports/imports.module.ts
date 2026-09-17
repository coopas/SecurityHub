import { NgModule } from '@angular/core';

import { SharedModule } from '../../shared/shared.module';
import { ImportListComponent } from './import-list/import-list.component';
import { ImportPreviewComponent } from './import-preview/import-preview.component';
import { ImportUploadComponent } from './import-upload/import-upload.component';
import { ImportsRoutingModule } from './imports-routing.module';

@NgModule({
  declarations: [ImportListComponent, ImportUploadComponent, ImportPreviewComponent],
  imports: [SharedModule, ImportsRoutingModule],
})
export class ImportsModule {}
