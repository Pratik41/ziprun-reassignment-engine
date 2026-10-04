import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface SuggestionStreamHandlers {
  start?: (strategy: string) => void;
  token: (text: string) => void;
  restart: (reason: string) => void;
  suggestion: (suggestion: any) => void;
  error: (message: string) => void;
}

@Injectable({
  providedIn: 'root'
})
export class ApiService {
  private apiUrl = 'http://localhost:8080';

  constructor(private http: HttpClient) {}

  getOrders(): Observable<any[]> {
    return this.http.get<any[]>(`${this.apiUrl}/orders`);
  }

  getOrdersByStatus(status: string): Observable<any[]> {
    return this.http.get<any[]>(`${this.apiUrl}/orders?status=${status}`);
  }

  createOrder(order: any): Observable<any> {
    return this.http.post<any>(`${this.apiUrl}/orders`, order);
  }

  getSuggestion(orderId: string): Observable<any> {
    return this.http.post<any>(`${this.apiUrl}/orders/${orderId}/suggest`, {});
  }

  acceptSuggestion(suggestionId: string): Observable<any> {
    return this.http.patch<any>(`${this.apiUrl}/suggestions/${suggestionId}`, {
      status: 'ACCEPTED'
    });
  }

  rejectSuggestion(suggestionId: string): Observable<any> {
    return this.http.patch<any>(`${this.apiUrl}/suggestions/${suggestionId}`, {
      status: 'REJECTED'
    });
  }

  getSuggestions(): Observable<any[]> {
    return this.http.get<any[]>(`${this.apiUrl}/suggestions`);
  }

  getAgents(): Observable<any[]> {
    return this.http.get<any[]>(`${this.apiUrl}/agents`);
  }

  updateAgentStatus(agentId: string, status: string): Observable<any> {
    return this.http.patch<any>(`${this.apiUrl}/agents/${agentId}/status`, {
      status
    });
  }

  getRoutingStrategy(): Observable<{ active: string; available: string[] }> {
    return this.http.get<{ active: string; available: string[] }>(`${this.apiUrl}/routing/strategy`);
  }

  setRoutingStrategy(strategy: string): Observable<{ active: string; available: string[] }> {
    return this.http.put<{ active: string; available: string[] }>(`${this.apiUrl}/routing/strategy`, { strategy });
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
          headers: { Accept: 'text/event-stream' },
          signal: controller.signal
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
      } catch (e: any) {
        if (e?.name !== 'AbortError') {
          handlers.error(e?.message ?? 'Stream failed');
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
      case 'suggestion': handlers.suggestion(payload); return true;
      case 'error': handlers.error(payload.message); return true;
      default: return false;
    }
  }

  keepWithCurrentAgent(orderId: string): Observable<any> {
    return this.http.post<any>(`${this.apiUrl}/orders/${orderId}/keep`, {});
  }

  manualReassign(orderId: string, newAgentId: string): Observable<any> {
    return this.http.post<any>(`${this.apiUrl}/orders/${orderId}/reassign`, {
      newAgentId
    });
  }
}
