CREATE TABLE IF NOT EXISTS mq_consume_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,

    event_id VARCHAR(100) NOT NULL,

    consumed_at DATETIME NOT NULL
        DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_event_id (event_id)
) ENGINE=InnoDB;