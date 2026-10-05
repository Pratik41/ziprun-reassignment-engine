import { Injectable, signal } from '@angular/core';

/** Light/dark theme, remembered per browser. index.html applies it before boot to avoid a flash. */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private static readonly KEY = 'ziprun-theme';

  readonly isDark = signal(document.documentElement.getAttribute('data-theme') === 'dark');

  toggle(): void {
    const dark = !this.isDark();
    this.isDark.set(dark);
    if (dark) {
      document.documentElement.setAttribute('data-theme', 'dark');
    } else {
      document.documentElement.removeAttribute('data-theme');
    }
    try {
      localStorage.setItem(ThemeService.KEY, dark ? 'dark' : 'light');
    } catch {
      // storage unavailable (private mode): theme just won't persist
    }
  }
}
