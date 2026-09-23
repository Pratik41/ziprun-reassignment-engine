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

  getAgents(): Observable<any[]> {
    return this.http.get<any[]>(`${this.apiUrl}/agents`);
  }

  updateAgentStatus(agentId: string, status: string): Observable<any> {
    return this.http.patch<any>(`${this.apiUrl}/agents/${agentId}/status`, {
      status
    });
  }
}
