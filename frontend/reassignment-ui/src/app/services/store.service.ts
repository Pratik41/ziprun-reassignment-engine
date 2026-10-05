import { Injectable, OnDestroy, computed, inject, signal } from '@angular/core';
import { forkJoin } from 'rxjs';
import { Agent, Order, StrategyInfo, Suggestion } from '../models';
import { ApiService } from './api.service';

/**
 * Single source of truth for the UI. Polls the backend every few seconds so
 * suggestions created by the background re-planning loop appear without a
 * click, and exposes derived views as signals.
 */
@Injectable({ providedIn: 'root' })
export class StoreService implements OnDestroy {
  static readonly POLL_MS = 3000;

  private readonly api = inject(ApiService);
  private readonly timer: ReturnType<typeof setInterval>;
  private inFlight = false;

  readonly agents = signal<Agent[]>([]);
  readonly orders = signal<Order[]>([]);
  readonly suggestions = signal<Suggestion[]>([]);
  readonly strategy = signal<StrategyInfo | null>(null);
  readonly loaded = signal(false);
  readonly connection = signal<'ok' | 'error'>('ok');
  readonly lastUpdated = signal<Date | null>(null);

  readonly agentById = computed(() => new Map(this.agents().map(a => [a.id, a])));

  /** Open (PENDING) suggestions per order, newest first. */
  readonly openSuggestionsByOrder = computed(() => {
    const map = new Map<string, Suggestion[]>();
    for (const s of this.suggestions()) {
      if (s.status !== 'PENDING') continue;
      const list = map.get(s.orderId) ?? [];
      list.push(s);
      map.set(s.orderId, list);
    }
    map.forEach(list => list.sort((a, b) => b.createdAt.localeCompare(a.createdAt)));
    return map;
  });

  /** Orders waiting for a new agent, newest first. */
  readonly waitingOrders = computed(() =>
    this.orders()
      .filter(o => o.status === 'REASSIGNMENT_PENDING')
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt)));

  readonly pendingSuggestions = computed(() => this.suggestions().filter(s => s.status === 'PENDING'));

  readonly availableAgents = computed(() =>
    this.agents()
      .filter(a => a.status === 'AVAILABLE')
      .sort((a, b) => a.activeOrderCount - b.activeOrderCount));

  readonly agentCounts = computed(() => {
    const counts = { AVAILABLE: 0, BUSY: 0, OFFLINE: 0 };
    this.agents().forEach(a => counts[a.status]++);
    return counts;
  });

  /** Pending suggestions recommending each agent (part of their effective load). */
  readonly pendingByAgent = computed(() => {
    const map = new Map<string, number>();
    this.pendingSuggestions().forEach(s => map.set(s.recommendedAgentId, (map.get(s.recommendedAgentId) ?? 0) + 1));
    return map;
  });

  constructor() {
    this.refresh();
    this.timer = setInterval(() => this.refresh(), StoreService.POLL_MS);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  refresh(): void {
    if (this.inFlight) {
      return;
    }
    this.inFlight = true;
    forkJoin({
      agents: this.api.getAgents(),
      orders: this.api.getOrders(),
      suggestions: this.api.getSuggestions(),
      strategy: this.api.getRoutingStrategy(),
    }).subscribe({
      next: data => {
        this.agents.set(data.agents);
        this.orders.set(data.orders);
        this.suggestions.set(data.suggestions);
        this.strategy.set(data.strategy);
        this.connection.set('ok');
        this.loaded.set(true);
        this.lastUpdated.set(new Date());
        this.inFlight = false;
      },
      error: () => {
        this.connection.set('error');
        this.inFlight = false;
      },
    });
  }

  agentName(id: string): string {
    return this.agentById().get(id)?.name ?? id;
  }

  /** True when this agent is the only AVAILABLE one (backend refuses to take them off duty). */
  isLastAvailable(agent: Agent): boolean {
    return agent.status === 'AVAILABLE' && this.agentCounts().AVAILABLE === 1;
  }
}
