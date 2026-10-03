import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

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

  manualReassign(orderId: string, newAgentId: string): Observable<any> {
    return this.http.post<any>(`${this.apiUrl}/orders/${orderId}/reassign`, {
      newAgentId
    });
  }
}
