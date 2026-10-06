import { ChangeDetectionStrategy, Component, Input, computed, inject, signal } from '@angular/core';
import { deadlineState } from '../labels';
import { StoreService } from '../services/store.service';
import { IconComponent } from './icon.component';

/** "Due 14:30" / "Due in 20m" (at risk) / "Late 12m" for an order's delivery deadline. */
@Component({
  selector: 'app-due-badge',
  standalone: true,
  imports: [IconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (state(); as s) {
      <span class="badge" [class]="'badge ' + tone[s.kind]" [attr.title]="s.title">
        <app-icon name="clock" [size]="12" />{{ s.label }}
      </span>
    }
  `,
})
export class DueBadgeComponent {
  private readonly store = inject(StoreService);
  private readonly deadline = signal<string | null>(null);

  @Input() set due(value: string | null | undefined) {
    this.deadline.set(value ?? null);
  }

  readonly tone = { late: 'tone-danger', risk: 'tone-warning', ok: 'tone-neutral' };

  /** Re-evaluated on every store refresh, so "Due in 20m" counts down. */
  readonly state = computed(() =>
    deadlineState(this.deadline(), this.store.lastUpdated() ?? new Date(), this.store.config()?.slaAtRiskMinutes ?? 30));
}
