-- Zones, capacity and delivery deadlines.
-- agents.current_zone / max_capacity and orders.pickup_zone / dropoff_zone / sla_deadline
-- already exist (V1). New: when the SLA monitor flagged an order as at risk.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS sla_alerted_at TIMESTAMP;

-- Give the sample agents and orders locations, but only where the row is still the
-- untouched sample (same id, name/description and no zone yet), never your own data.
UPDATE agents SET current_zone = 'KORAMANGALA'   WHERE id = 'AGT-001' AND name = 'Priya Sharma' AND current_zone IS NULL;
UPDATE agents SET current_zone = 'HSR_LAYOUT'    WHERE id = 'AGT-002' AND name = 'Rahul Verma'  AND current_zone IS NULL;
UPDATE agents SET current_zone = 'WHITEFIELD'    WHERE id = 'AGT-003' AND name = 'Ananya Iyer'  AND current_zone IS NULL;
UPDATE agents SET current_zone = 'MALLESHWARAM'  WHERE id = 'AGT-004' AND name = 'Kiran Nair'   AND current_zone IS NULL;
UPDATE agents SET current_zone = 'JAYANAGAR'     WHERE id = 'AGT-005' AND name = 'Deepak Mehta' AND current_zone IS NULL;

UPDATE orders SET pickup_zone = 'KORAMANGALA',  dropoff_zone = 'INDIRANAGAR'     WHERE id = 'ORD-001' AND description = 'Electronics - Koramangala to Indiranagar' AND pickup_zone IS NULL;
UPDATE orders SET pickup_zone = 'HSR_LAYOUT',   dropoff_zone = 'BTM_LAYOUT'      WHERE id = 'ORD-002' AND description = 'Groceries - HSR Layout to BTM'            AND pickup_zone IS NULL;
UPDATE orders SET pickup_zone = 'WHITEFIELD',   dropoff_zone = 'MARATHAHALLI'    WHERE id = 'ORD-003' AND description = 'Pharma - Whitefield to Marathahalli'      AND pickup_zone IS NULL;
UPDATE orders SET pickup_zone = 'MG_ROAD',      dropoff_zone = 'JAYANAGAR'       WHERE id = 'ORD-004' AND description = 'Documents - MG Road to Jayanagar'         AND pickup_zone IS NULL;
UPDATE orders SET pickup_zone = 'BELLANDUR',    dropoff_zone = 'ELECTRONIC_CITY' WHERE id = 'ORD-005' AND description = 'Food - Bellandur to Electronic City'      AND pickup_zone IS NULL;
UPDATE orders SET pickup_zone = 'MALLESHWARAM', dropoff_zone = 'RAJAJINAGAR'     WHERE id = 'ORD-006' AND description = 'Apparel - Malleshwaram to Rajajinagar'    AND pickup_zone IS NULL;
UPDATE orders SET pickup_zone = 'BANASHANKARI', dropoff_zone = 'JP_NAGAR'        WHERE id = 'ORD-007' AND description = 'Books - Banashankari to JP Nagar'         AND pickup_zone IS NULL;
UPDATE orders SET pickup_zone = 'PEENYA',       dropoff_zone = 'YESHWANTHPUR'    WHERE id = 'ORD-008' AND description = 'Hardware - Peenya to Yeshwanthpur'        AND pickup_zone IS NULL;
