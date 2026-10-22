SET SESSION TRANSACTION READ ONLY; START TRANSACTION;
SELECT NOW(3) AS observed_db_time,UTC_TIMESTAMP(3) AS observed_utc_time;
SELECT s.id,s.resource_id,c.name,s.status,COUNT(r.id) AS total_reservations,COALESCE(SUM(r.status IN ('PENDING','CONFIRMED')),0) AS effective_reservations FROM resource_slot s JOIN compute_resource c ON c.id=s.resource_id LEFT JOIN reservation r ON r.slot_id=s.id WHERE s.id IN (117,118,119) GROUP BY s.id,s.resource_id,c.name,s.status ORDER BY s.id;
SELECT id,user_id,slot_id,status,request_id,created_at,expire_at FROM reservation WHERE slot_id IN (117,118,119) ORDER BY slot_id;
SELECT id,event_id,consumed_at FROM mq_consume_record WHERE event_id IN ('reservation-expire-57','reservation-expire-58','reservation-expire-59') ORDER BY id;
COMMIT;
