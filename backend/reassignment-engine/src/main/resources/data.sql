INSERT INTO agents (id, name, status, active_order_count, current_zone) SELECT 'AGT-001', 'Raj Kumar', 'AVAILABLE', 0, NULL WHERE NOT EXISTS (SELECT 1 FROM agents WHERE id='AGT-001');
INSERT INTO agents (id, name, status, active_order_count, current_zone) SELECT 'AGT-002', 'Amit Patel', 'AVAILABLE', 0, NULL WHERE NOT EXISTS (SELECT 1 FROM agents WHERE id='AGT-002');
INSERT INTO agents (id, name, status, active_order_count, current_zone) SELECT 'AGT-003', 'Vikram Singh', 'AVAILABLE', 0, NULL WHERE NOT EXISTS (SELECT 1 FROM agents WHERE id='AGT-003');
INSERT INTO agents (id, name, status, active_order_count, current_zone) SELECT 'AGT-004', 'Suresh Gupta', 'AVAILABLE', 0, NULL WHERE NOT EXISTS (SELECT 1 FROM agents WHERE id='AGT-004');
INSERT INTO agents (id, name, status, active_order_count, current_zone) SELECT 'AGT-005', 'Ravi Nair', 'AVAILABLE', 0, NULL WHERE NOT EXISTS (SELECT 1 FROM agents WHERE id='AGT-005');
