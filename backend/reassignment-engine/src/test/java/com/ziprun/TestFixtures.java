package com.ziprun;

import com.ziprun.domain.Agent;
import com.ziprun.domain.AgentStatus;
import com.ziprun.domain.Order;

public final class TestFixtures {

    private TestFixtures() {
    }

    public static Agent agent(String id, int load) {
        Agent a = new Agent();
        a.setId(id);
        a.setName("Name " + id);
        a.setStatus(AgentStatus.AVAILABLE);
        a.setActiveOrderCount(load);
        return a;
    }

    public static Order order(String id) {
        Order o = new Order();
        o.setId(id);
        o.setDescription("Parcel " + id);
        o.setAssignedAgentId("AGT-9");
        return o;
    }
}
