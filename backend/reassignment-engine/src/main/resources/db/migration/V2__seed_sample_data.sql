-- Sample data: 5 agents, 8 pre-assigned orders (Bengaluru routes).
-- Only fills an empty database, so existing data is never touched.
-- active_order_count matches the number of orders assigned to each agent.

INSERT INTO agents (id, name, active_order_count, status)
SELECT id, name, active_order_count, status FROM (VALUES
  ('AGT-001', 'Priya Sharma', 3, 'BUSY'),
  ('AGT-002', 'Rahul Verma',  0, 'AVAILABLE'),
  ('AGT-003', 'Ananya Iyer',  2, 'BUSY'),
  ('AGT-004', 'Kiran Nair',   0, 'AVAILABLE'),
  ('AGT-005', 'Deepak Mehta', 3, 'BUSY')
) AS seed(id, name, active_order_count, status)
WHERE NOT EXISTS (SELECT 1 FROM agents);

INSERT INTO orders (id, description, assigned_agent_id, status, created_at)
SELECT id, description, assigned_agent_id, status, CURRENT_TIMESTAMP FROM (VALUES
  ('ORD-001', 'Electronics - Koramangala to Indiranagar', 'AGT-001', 'ASSIGNED'),
  ('ORD-002', 'Groceries - HSR Layout to BTM',            'AGT-001', 'ASSIGNED'),
  ('ORD-003', 'Pharma - Whitefield to Marathahalli',      'AGT-003', 'ASSIGNED'),
  ('ORD-004', 'Documents - MG Road to Jayanagar',         'AGT-005', 'ASSIGNED'),
  ('ORD-005', 'Food - Bellandur to Electronic City',      'AGT-005', 'ASSIGNED'),
  ('ORD-006', 'Apparel - Malleshwaram to Rajajinagar',    'AGT-005', 'ASSIGNED'),
  ('ORD-007', 'Books - Banashankari to JP Nagar',         'AGT-003', 'ASSIGNED'),
  ('ORD-008', 'Hardware - Peenya to Yeshwanthpur',        'AGT-001', 'ASSIGNED')
) AS seed(id, description, assigned_agent_id, status)
WHERE NOT EXISTS (SELECT 1 FROM orders);
