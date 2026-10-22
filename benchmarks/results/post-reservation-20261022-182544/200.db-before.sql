SET SESSION TRANSACTION READ ONLY; START TRANSACTION;
SELECT NOW(3) AS observed_db_time,UTC_TIMESTAMP(3) AS observed_utc_time,@@session.time_zone AS session_time_zone;
SELECT s.id,s.resource_id,c.name,s.slot_start,s.slot_end,s.status FROM resource_slot s JOIN compute_resource c ON c.id=s.resource_id WHERE s.id=119;
SELECT status,COUNT(*) AS rows_in_status FROM reservation WHERE slot_id=119 GROUP BY status;
SELECT COUNT(*) AS effective_count FROM reservation WHERE slot_id=119 AND status IN ('PENDING','CONFIRMED');
SELECT id,user_id,slot_id,status,request_id,created_at,expire_at FROM reservation WHERE slot_id=119 ORDER BY id;
COMMIT;
