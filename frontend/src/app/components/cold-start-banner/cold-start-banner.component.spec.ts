import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { ColdStartBannerComponent } from './cold-start-banner.component';

describe('ColdStartBannerComponent', () => {
  let fixture: ComponentFixture<ColdStartBannerComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ColdStartBannerComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(ColdStartBannerComponent);
    fixture.detectChanges();
  });

  it('should not render banner when visible is false', () => {
    fixture.componentRef.setInput('visible', false);
    fixture.detectChanges();

    const banner = fixture.nativeElement.querySelector('.cold-start-banner');
    expect(banner).toBeNull();
  });

  it('should render message when visible is true', () => {
    fixture.componentRef.setInput('visible', true);
    fixture.detectChanges();

    const banner = fixture.nativeElement.querySelector('.cold-start-banner');
    expect(banner).toBeTruthy();
    expect(banner.textContent).toContain(
      'Waking up the backend (it scales to zero; the first request can take ~30 s)'
    );
  });
});
