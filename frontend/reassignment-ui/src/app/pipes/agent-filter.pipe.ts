import { Pipe, PipeTransform } from '@angular/core';

@Pipe({
  name: 'agentFilter',
  standalone: true
})
export class AgentFilterPipe implements PipeTransform {
  transform(agents: any[], status: string): any[] {
    if (!agents) return [];
    return agents.filter(agent => agent.status === status);
  }
}
