START TRANSACTION;
INSERT INTO resource_slot(resource_id,slot_start,slot_end,status) SELECT id,'2099-01-24 09:00:00','2099-01-24 10:00:00','AVAILABLE' FROM compute_resource WHERE id=112 AND name='DAY24_BENCH_ONLY_d24_20261022_182544_3163fc';
INSERT INTO resource_slot(resource_id,slot_start,slot_end,status) SELECT id,'2099-01-24 10:00:00','2099-01-24 11:00:00','AVAILABLE' FROM compute_resource WHERE id=112 AND name='DAY24_BENCH_ONLY_d24_20261022_182544_3163fc';
INSERT INTO resource_slot(resource_id,slot_start,slot_end,status) SELECT id,'2099-01-24 11:00:00','2099-01-24 12:00:00','AVAILABLE' FROM compute_resource WHERE id=112 AND name='DAY24_BENCH_ONLY_d24_20261022_182544_3163fc';
COMMIT;
