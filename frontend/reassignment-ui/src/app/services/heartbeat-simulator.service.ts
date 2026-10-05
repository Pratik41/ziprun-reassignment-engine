import { Injectable, OnDestroy, inject, signal } from '@angular/core';
import { ApiService } from './api.service';

/**
 * Stands in for agents' phone apps so automatic offline detection can be tried
 * from the browser: while "connected", sends a heartbeat for that agent every
 * few seconds. Disconnect one and the backend marks the agent OFFLINE once the
 * heartbeat timeout (default 60s) passes. Lives only in this browser tab.
 */
@Injectable({ providedIn: 'root' })
export class HeartbeatSimulatorService implements OnDestroy {
  static readonly INTERVAL_MS = 10_000;

  private readonly api = inject(ApiService);
  private readonly timer = setInterval(() => this.beatAll(), HeartbeatSimulatorService.INTERVAL_MS);

  /** Agent ids whose simulated app is connected. */
  readonly connected = signal<ReadonlySet<string>>(new Set());

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  isConnected(agentId: string): boolean {
    return this.connected().has(agentId);
  }

  connect(agentId: string): void {
    this.connected.update(s => new Set(s).add(agentId));
    this.beat(agentId);
  }

  disconnect(agentId: string): void {
    this.connected.update(s => {
      const next = new Set(s);
      next.delete(agentId);
      return next;
    });
  }

  private beatAll(): void {
    this.connected().forEach(id => this.beat(id));
  }

  private beat(agentId: string): void {
    this.api.sendHeartbeat(agentId).subscribe({ error: () => { /* backend down: the monitor will notice */ } });
  }
}
