import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Activity, Agent, AgentStatus, AppConfig, MetricsSummary, NewOrderRequest, Order, OrderStatus, RecommendResponse, StrategyInfo, Suggestion } from '../models';

export interface SuggestionStreamHandlers {
  start?: (strategy: string) => void;
  token: (text: string) => void;
  restart: (reason: string) => void;
  suggestion: (suggestion: Suggestion) => void;
  error: (message: string) => void;
}

function readCookie(name: string): string | null {
  const match = document.cookie.split('; ').find(c => c.startsWith(name + '='));
  return match ? decodeURIComponent(match.slice(name.length + 1)) : null;
}

/** The server's message from an error response ({status, error, message, ...}), or a fallback. */
export function errorMessage(err: unknown, fallback = 'Something went wrong'): string {
  if (err instanceof HttpErrorResponse) {
    if (err.status === 0) {
      return 'Cannot reach the backend';
    }
    const body = err.error as { message?: string } | null;
    return body?.message ?? fallback;
  }
  return fallback;
}

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  /**
   * Same origin as the UI: `ng serve` (proxy.conf.json) and nginx (Docker) forward
   * /api/* to the backend, so the app works from any host without configuration.
   */
  private readonly apiUrl = '/api';

  getAgents(): Observable<Agent[]> {
    return this.http.get<Agent[]>(`${this.apiUrl}/agents`);
  }

  getOrders(): Observable<Order[]> {
    return this.http.get<Order[]>(`${this.apiUrl}/orders`);
  }

  /** Open (PENDING) suggestions only: all the console needs. History is on the Insights page. */
  getOpenSuggestions(): Observable<Suggestion[]> {
    return this.http.get<Suggestion[]>(`${this.apiUrl}/suggestions`, { params: { status: 'PENDING' } });
  }

  /** recommendedAgentId: the top pick shown in the dialog, so Insights can track how often it's followed. */
  createOrder(order: NewOrderRequest): Observable<Order> {
    return this.http.post<Order>(`${this.apiUrl}/orders`, order);
  }

  /** Ranks Available agents for an order that doesn't exist yet. Nothing is saved. */
  recommendAgents(description: string, pickupZone: string | null, dropoffZone: string | null): Observable<RecommendResponse> {
    return this.http.post<RecommendResponse>(`${this.apiUrl}/routing/recommend`, { description, pickupZone, dropoffZone });
  }

  /** Live change notifications (Server-Sent Events). */
  openEvents(): EventSource {
    return new EventSource(`${this.apiUrl}/events`, { withCredentials: true });
  }

  getConfig(): Observable<AppConfig> {
    return this.http.get<AppConfig>(`${this.apiUrl}/config`);
  }

  /** Zone and capacity; null zone = unknown, null capacity = fleet default. */
  updateAgent(agentId: string, currentZone: string | null, maxCapacity: number | null): Observable<Agent> {
    return this.http.patch<Agent>(`${this.apiUrl}/agents/${agentId}`, { currentZone, maxCapacity });
  }

  updateOrderStatus(orderId: string, status: OrderStatus): Observable<Order> {
    return this.http.patch<Order>(`${this.apiUrl}/orders/${orderId}/status`, { status });
  }

  updateAgentStatus(agentId: string, status: AgentStatus): Observable<Agent> {
    return this.http.patch<Agent>(`${this.apiUrl}/agents/${agentId}/status`, { status });
  }

  decideSuggestion(suggestionId: string, status: 'ACCEPTED' | 'REJECTED'): Observable<Suggestion> {
    return this.http.patch<Suggestion>(`${this.apiUrl}/suggestions/${suggestionId}`, { status });
  }

  manualReassign(orderId: string, newAgentId: string): Observable<Order> {
    return this.http.post<Order>(`${this.apiUrl}/orders/${orderId}/reassign`, { newAgentId });
  }

  keepWithCurrentAgent(orderId: string): Observable<Order> {
    return this.http.post<Order>(`${this.apiUrl}/orders/${orderId}/keep`, {});
  }

  sendHeartbeat(agentId: string): Observable<Agent> {
    return this.http.post<Agent>(`${this.apiUrl}/agents/${agentId}/heartbeat`, {});
  }

  getActivity(limit = 60): Observable<Activity[]> {
    return this.http.get<Activity[]>(`${this.apiUrl}/activity`, { params: { limit } });
  }

  getMetrics(): Observable<MetricsSummary> {
    return this.http.get<MetricsSummary>(`${this.apiUrl}/metrics`);
  }

  getRoutingStrategy(): Observable<StrategyInfo> {
    return this.http.get<StrategyInfo>(`${this.apiUrl}/routing/strategy`);
  }

  setRoutingStrategy(strategy: string): Observable<StrategyInfo> {
    return this.http.put<StrategyInfo>(`${this.apiUrl}/routing/strategy`, { strategy });
  }

  /**
   * POST /orders/{id}/suggest/stream. Uses fetch rather than EventSource
   * because EventSource can only GET. Returns a function that aborts the stream.
   */
  streamSuggestion(orderId: string, handlers: SuggestionStreamHandlers): () => void {
    const controller = new AbortController();

    (async () => {
      try {
        const response = await fetch(`${this.apiUrl}/orders/${orderId}/suggest/stream`, {
          method: 'POST',
          // fetch isn't HttpClient, so echo the CSRF cookie ourselves
          headers: { Accept: 'text/event-stream', 'X-XSRF-TOKEN': readCookie('XSRF-TOKEN') ?? '' },
          credentials: 'same-origin',
          signal: controller.signal,
        });
        if (!response.ok || !response.body) {
          const body = await response.json().catch(() => null);
          handlers.error(body?.message ?? `HTTP ${response.status}`);
          return;
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        let finished = false;
        while (!finished) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });

          // SSE frames are separated by a blank line
          let boundary: number;
          while ((boundary = buffer.indexOf('\n\n')) >= 0) {
            const frame = buffer.slice(0, boundary);
            buffer = buffer.slice(boundary + 2);
            finished = this.dispatchSseFrame(frame, handlers) || finished;
          }
        }
        if (!finished) {
          handlers.error('Stream ended without a suggestion');
        }
      } catch (e: unknown) {
        if ((e as { name?: string })?.name !== 'AbortError') {
          handlers.error((e as { message?: string })?.message ?? 'Stream failed');
        }
      }
    })();

    return () => controller.abort();
  }

  /** @returns true when the frame ends the stream (suggestion or error) */
  private dispatchSseFrame(frame: string, handlers: SuggestionStreamHandlers): boolean {
    let event = 'message';
    const data: string[] = [];
    for (const line of frame.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data.push(line.slice(5));
    }
    const payload = data.length ? JSON.parse(data.join('\n')) : {};

    switch (event) {
      case 'start': handlers.start?.(payload.strategy); return false;
      case 'token': handlers.token(payload.text); return false;
      case 'restart': handlers.restart(payload.reason); return false;
      case 'suggestion': handlers.suggestion(payload as Suggestion); return true;
      case 'error': handlers.error(payload.message); return true;
      default: return false;
    }
  }
}
