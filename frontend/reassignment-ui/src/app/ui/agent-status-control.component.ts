import { ChangeDetectionStrategy, Component, Input, inject, signal } from '@angular/core';
import { Agent, AgentStatus } from '../models';
import { AGENT_STATUS, AGENT_STATUSES } from '../labels';
import { ApiService, errorMessage } from '../services/api.service';
import { StoreService } from '../services/store.service';
import { ToastService } from '../services/toast.service';

/**
 * Available / Busy / Offline switch for one agent. Changing status triggers the
 * backend's re-planning loop. Mirrors the backend rule that the last AVAILABLE
 * agent can't go off duty.
 */
@Component({
  selector: 'app-agent-status-control',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="seg" [class.seg-sm]="compact" [class.seg-stretch]="stretch" role="group" [attr.aria-label]="'Status of ' + agent.name">
      @for (s of statuses; track s) {
        <button type="button"
                [class.on]="agent.status === s"
                [disabled]="saving() || isBlocked(s)"
                [attr.title]="isBlocked(s) ? lastAvailableHint : labels[s].hint"
                (click)="set(s)">
          <span class="status-dot" [class]="'status-dot ' + s"></span>
          {{ compact && !stretch ? short[s] : labels[s].label }}
        </button>
      }
    </div>
  `,
  styles: [`
    :host { display: block; min-width: 0; }
    .seg-stretch { display: flex; width: 100%; }
    .seg-stretch button { flex: 1; justify-content: center; min-width: 0; }
    .status-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--text-3); }
    .status-dot.AVAILABLE { background: var(--success); }
    .status-dot.BUSY { background: var(--warning); }
    .status-dot.OFFLINE { background: var(--danger); }
  `],
})
export class AgentStatusControlComponent {
  private readonly api = inject(ApiService);
  private readonly store = inject(StoreService);
  private readonly toast = inject(ToastService);

  @Input({ required: true }) agent!: Agent;
  @Input() compact = false;
  /** Fill the available width with equal buttons (never clipped in narrow panels). */
  @Input() stretch = false;

  readonly statuses = AGENT_STATUSES;
  readonly labels = AGENT_STATUS;
  readonly short: Record<AgentStatus, string> = { AVAILABLE: 'Avail.', BUSY: 'Busy', OFFLINE: 'Off' };
  readonly lastAvailableHint = 'Only Available agent: make someone else Available first';
  readonly saving = signal(false);

  isBlocked(s: AgentStatus): boolean {
    return s !== 'AVAILABLE' && this.store.isLastAvailable(this.agent);
  }

  set(status: AgentStatus): void {
    if (status === this.agent.status) {
      return;
    }
    this.saving.set(true);
    this.api.updateAgentStatus(this.agent.id, status).subscribe({
      next: () => {
        this.saving.set(false);
        this.toast.success(`${this.agent.name} is now ${AGENT_STATUS[status].label}`, this.consequence(status));
        this.store.refresh();
      },
      error: err => {
        this.saving.set(false);
        this.toast.error(`Couldn't update ${this.agent.name}`, errorMessage(err));
      },
    });
  }

  private consequence(status: AgentStatus): string {
    switch (status) {
      case 'OFFLINE': return 'Their orders are being re-planned in the background.';
      case 'BUSY': return 'They keep their orders but won\'t be suggested for new ones.';
      default: return 'Waiting orders are being re-balanced across the fleet.';
    }
  }
}
