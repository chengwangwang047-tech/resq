-- Private, disposable resq_day17 ONLY. No USE resq / production connection.
-- Based on checked-in Day08 DDL and current mapper column contracts.
-- Live SHOW CREATE TABLE verification is recorded separately by the acceptance report.
CREATE TABLE `user` (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 username VARCHAR(50) NOT NULL,
 password VARCHAR(100) NOT NULL,
 email VARCHAR(100) UNIQUE,
 created_at DATETIME NOT NULL
) ENGINE=InnoDB;
CREATE TABLE compute_resource (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 name VARCHAR(100) NOT NULL,
 type VARCHAR(20) NOT NULL,
 status VARCHAR(20) NOT NULL,
 created_at DATETIME NOT NULL
) ENGINE=InnoDB;
CREATE TABLE resource_slot (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 resource_id BIGINT NOT NULL,
 slot_start DATETIME NOT NULL,
 slot_end DATETIME NOT NULL,
 status VARCHAR(20) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE reservation (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 user_id BIGINT NOT NULL,
 slot_id BIGINT NOT NULL,
 status VARCHAR(20) NOT NULL,
 request_id VARCHAR(100) UNIQUE,
 created_at DATETIME NOT NULL
) ENGINE=InnoDB;
