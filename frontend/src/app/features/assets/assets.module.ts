import { NgModule } from '@angular/core';

import { SharedModule } from '../../shared/shared.module';
import { AssetDetailComponent } from './asset-detail/asset-detail.component';
import { AssetFormComponent } from './asset-form/asset-form.component';
import { AssetListComponent } from './asset-list/asset-list.component';
import { AssetsRoutingModule } from './assets-routing.module';

@NgModule({
  declarations: [AssetListComponent, AssetFormComponent, AssetDetailComponent],
  imports: [SharedModule, AssetsRoutingModule],
})
export class AssetsModule {}
