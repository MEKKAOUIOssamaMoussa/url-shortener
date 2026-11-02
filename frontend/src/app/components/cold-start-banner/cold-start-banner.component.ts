import { Component, input } from '@angular/core';

@Component({
  selector: 'app-cold-start-banner',
  standalone: true,
  templateUrl: './cold-start-banner.component.html',
  styleUrl: './cold-start-banner.component.css',
})
export class ColdStartBannerComponent {
  readonly visible = input<boolean>(false);
}
