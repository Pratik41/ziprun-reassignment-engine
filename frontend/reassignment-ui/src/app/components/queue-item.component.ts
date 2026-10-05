import { ChangeDetectionStrategy, Component, Input, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { Order, Suggestion } from '../models';
import { AGENT_STATUS, CONFIDENCE_LABEL, confidenceLevel, percent, sourceInfo, timeAgo } from '../labels';
import { ApiService, errorMessage } from '../services/api.service';
import { StoreService } from '../services/store.service';
import { ToastService } from '../services/toast.service';
import { AvatarComponent } from '../ui/avatar.component';
import { IconComponent } from '../ui/icon.component';

interface LiveReasoning {
  strategy: string;
  text: string;
  notice: string | null;
}

/**
 * One order waiting for a new agent: its open suggestion(s) with reasoning and
 * accept/reject, or (if none) a streamed "Get suggestion", plus manual reassign
 * and "keep with original agent".
 */
@Component({
  selector: 'app-queue-item',
  standalone: true,
  imports: [FormsModule, AvatarComponent, IconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './queue-item.component.html',
  styleUrl: './queue-item.component.css',
})
export class QueueItemComponent implements OnDestroy {
  private readonly api = inject(ApiService);
  readonly store = inject(StoreService);
  private readonly toast = inject(ToastService);

  @Input({ required: true }) order!: Order;

  readonly agentStatus = AGENT_STATUS;
  readonly confidenceLabel = CONFIDENCE_LABEL;
  readonly level = confidenceLevel;
  readonly percent = percent;
  readonly source = sourceInfo;

  readonly busy = signal<string | null>(null);
  readonly live = signal<LiveReasoning | null>(null);
  readonly reassignOpen = signal(false);
  reassignTarget = '';
  private cancelStream: (() => void) | null = null;

  readonly now = computed(() => this.store.lastUpdated() ?? new Date());

  ngOnDestroy(): void {
    this.cancelStream?.();
  }

  get suggestions(): Suggestion[] {
    return this.store.openSuggestionsByOrder().get(this.order.id) ?? [];
  }

  get originalAgent() {
    return this.store.agentById().get(this.order.assignedAgentId);
  }

  /** The order's own agent is AVAILABLE again, so ops can keep it with them. */
  get originalAgentBack(): boolean {
    return this.originalAgent?.status === 'AVAILABLE';
  }

  /** Manual targets: AVAILABLE only, not the order's own agent, least loaded first. */
  get reassignTargets() {
    return this.store.availableAgents().filter(a => a.id !== this.order.assignedAgentId);
  }

  ago(iso: string | null): string {
    return timeAgo(iso, this.now());
  }

  recommendedAvailable(s: Suggestion): boolean {
    return this.store.agentById().get(s.recommendedAgentId)?.status === 'AVAILABLE';
  }

  accept(s: Suggestion): void {
    const name = this.store.agentName(s.recommendedAgentId);
    this.run(s.id, this.api.decideSuggestion(s.id, 'ACCEPTED'),
      `${this.order.id} assigned to ${name}`, 'Couldn\'t accept the suggestion');
  }

  reject(s: Suggestion): void {
    this.run(s.id, this.api.decideSuggestion(s.id, 'REJECTED'),
      'Suggestion rejected', 'Couldn\'t reject the suggestion',
      'The order stays in the queue: get a new suggestion or reassign it manually.');
  }

  keep(): void {
    const name = this.originalAgent?.name ?? 'their agent';
    this.run('keep', this.api.keepWithCurrentAgent(this.order.id),
      `${this.order.id} stays with ${name}`, 'Couldn\'t keep the order');
  }

  submitReassign(): void {
    if (!this.reassignTarget) {
      return;
    }
    const name = this.store.agentName(this.reassignTarget);
    this.run('reassign', this.api.manualReassign(this.order.id, this.reassignTarget),
      `${this.order.id} reassigned to ${name}`, 'Couldn\'t reassign the order');
  }

  toggleReassign(): void {
    this.reassignOpen.update(v => !v);
    this.reassignTarget = this.reassignTargets[0]?.id ?? '';
  }

  /** Streams the reasoning while the strategy works, then the saved suggestion appears on refresh. */
  requestSuggestion(): void {
    const live: LiveReasoning = { strategy: '', text: '', notice: null };
    this.live.set(live);
    this.cancelStream = this.api.streamSuggestion(this.order.id, {
      start: strategy => this.live.set({ ...live, strategy }),
      token: text => this.live.update(l => (l ? { ...l, text: l.text + text } : l)),
      restart: reason => this.live.update(l => (l ? { ...l, text: '', notice: reason } : l)),
      suggestion: () => {
        this.live.set(null);
        this.store.refresh();
      },
      error: message => {
        this.live.set(null);
        this.toast.error('Couldn\'t get a suggestion', message);
      },
    });
  }

  private run(key: string, call: Observable<unknown>, success: string, failure: string, detail?: string): void {
    this.busy.set(key);
    call.subscribe({
      next: () => {
        this.busy.set(null);
        this.reassignOpen.set(false);
        this.toast.success(success, detail);
        this.store.refresh();
      },
      error: err => {
        this.busy.set(null);
        this.toast.error(failure, errorMessage(err));
        this.store.refresh();
      },
    });
  }
}
