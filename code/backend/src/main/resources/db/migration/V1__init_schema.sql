-- Flyway 도입 전까지 backup.sql에 있던 테이블 구조. 이미 운영 중인 DB에는 실행하지 않고 적용된 것으로 기록만 한다(baseline).

CREATE TABLE `users` (
                         `id` bigint NOT NULL AUTO_INCREMENT,
                         `customer_key` varchar(255) DEFAULT NULL,
                         `email` varchar(255) NOT NULL,
                         `email_verified_at` datetime(6) DEFAULT NULL,
                         `password` varchar(255) NOT NULL,
                         `phonenumber` varchar(255) DEFAULT NULL,
                         `username` varchar(255) NOT NULL,
                         `role` enum('ADMIN','DIRECTOR','USER') NOT NULL,
                         `status` enum('ACTIVE','INACTIVE','DELETED') NOT NULL,
                         PRIMARY KEY (`id`),
                         UNIQUE KEY `UK6dotkott2kjsp8vw4d0m25fb7` (`email`),
                         KEY `idx_users_phonenumber` (`phonenumber`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE email_verifications (
    id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email varchar(254) NOT NULL UNIQUE,
    purpose varchar(32) NOT NULL DEFAULT 'EMAIL_VERIFICATION',
    token_hash varchar(64) NOT NULL,
    code_hash varchar(100) NOT NULL,
    expires_at datetime(6) NOT NULL,
    sent_at datetime(6) NOT NULL,
    window_started_at datetime(6) NOT NULL,
    send_count int NOT NULL,
    failed_attempts int NOT NULL,
    verified_at datetime(6) NULL,
    consumed_at datetime(6) NULL
);

CREATE TABLE refresh_tokens (
    id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_refresh_tokens_expires_at (expires_at),
    KEY idx_refresh_tokens_user_id (user_id)
);

CREATE TABLE `events` (
                          `end_date` date NOT NULL,
                          `running_time` int DEFAULT NULL,
                          `start_date` date NOT NULL,
                          `id` bigint NOT NULL AUTO_INCREMENT,
                          `banner_image_url` varchar(255) NOT NULL,
                          `category` varchar(255) DEFAULT NULL,
                          `description` varchar(255) DEFAULT NULL,
                          `description_image_url` varchar(255) DEFAULT NULL,
                          `location` varchar(255) NOT NULL,
                          `main_image_url` varchar(255) NOT NULL,
                          `name` varchar(255) NOT NULL,
                          PRIMARY KEY (`id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `event_directors` (
                                   `event_id` bigint NOT NULL,
                                   `id` bigint NOT NULL AUTO_INCREMENT,
                                   `user_id` bigint NOT NULL,
                                   PRIMARY KEY (`id`),
                                   UNIQUE KEY `uk_event_director` (`event_id`, `user_id`),
                                   CONSTRAINT `FKo5sgrtonppbovvdosdf3if774`
                                       FOREIGN KEY (`event_id`) REFERENCES `events` (`id`),
                                   CONSTRAINT `FKphv132ir3mgfmlst3xt6skafk`
                                       FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `tickets` (
                           `initial_price` int DEFAULT NULL,
                           `min_price` int DEFAULT NULL,
                           `sales_start_at` datetime(6) DEFAULT NULL,
                           `last_price_evaluated_at` datetime(6) DEFAULT NULL,
                           `automatic_pricing_enabled` boolean NOT NULL DEFAULT FALSE,
                           `price` int DEFAULT NULL,
                           `sold_ticket` int DEFAULT NULL,
                           `total_ticket` int DEFAULT NULL,
                           `booking_endtime` datetime(6) DEFAULT NULL,
                           `event_id` bigint DEFAULT NULL,
                           `id` bigint NOT NULL AUTO_INCREMENT,
                           `start_time` datetime(6) DEFAULT NULL,
                           `description` varchar(255) DEFAULT NULL,
                           `type` varchar(255) NOT NULL,
                           PRIMARY KEY (`id`),
                           CONSTRAINT `FK3utafe14rupaypjocldjaj4ol`
                               FOREIGN KEY (`event_id`) REFERENCES `events` (`id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `ticket_price_history` (
                                        `id` bigint NOT NULL AUTO_INCREMENT,
                                        `ticket_id` bigint NOT NULL,
                                        `price` int NOT NULL,
                                        `changed_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                        PRIMARY KEY (`id`),
                                        CONSTRAINT `fk_ticket_price_history_ticket`
                                            FOREIGN KEY (`ticket_id`) REFERENCES `tickets` (`id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `orders` (
                          `quantity` int NOT NULL,
                          `event_id` bigint DEFAULT NULL,
                          `expires_at` datetime(6) DEFAULT NULL,
                          `id` bigint NOT NULL AUTO_INCREMENT,
                          `order_date` datetime(6) DEFAULT NULL,
                          `ticket_id` bigint DEFAULT NULL,
                          `user_id` bigint NOT NULL,
                          `order_id` varchar(64) NOT NULL,
                          `unit_price` int NOT NULL,
                          `total_amount` bigint NOT NULL,
                          `paid_at` datetime(6) DEFAULT NULL,
                          `idempotency_key` varchar(36) NOT NULL,
                          `payment_key` varchar(200) DEFAULT NULL,
                          `next_recovery_at` datetime(6) DEFAULT NULL,
                          `recovery_attempts` int NOT NULL DEFAULT 0,
                          `recovery_review_required` boolean NOT NULL DEFAULT FALSE,
                          `status` enum('CANCELED','CANCELING','CONFIRMING','EXPIRED','PAID','PAYMENT_FAILED','PENDING') DEFAULT NULL,
                          PRIMARY KEY (`id`),
                          UNIQUE KEY `UKhmsk25beh6atojvle1xuymjj0` (`order_id`),
                          UNIQUE KEY `uk_orders_idempotency_key` (`idempotency_key`),
                          KEY `idx_orders_ticket_id` (`ticket_id`),
                          CONSTRAINT `FK32ql8ubntj5uh44ph9659tiih`
                              FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE payment_reconciliation_issue (
    id BIGINT NOT NULL AUTO_INCREMENT,
    merchant_id VARCHAR(14) NOT NULL,
    transaction_key VARCHAR(64) NOT NULL,
    order_id VARCHAR(64) NOT NULL,
    pg_payment_key VARCHAR(200) NOT NULL,
    issue_type VARCHAR(40) NOT NULL,
    pg_status VARCHAR(30) NOT NULL,
    db_order_status VARCHAR(30) NULL,
    db_payment_status VARCHAR(30) NULL,
    transaction_at_utc DATETIME(6) NOT NULL,
    detected_at_utc DATETIME(6) NOT NULL,
    resolved_at_utc DATETIME(6) NULL,
    resolved_by BIGINT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_recon_transaction (merchant_id, transaction_key)
);

CREATE TABLE payment_reconciliation_action (
    id BIGINT NOT NULL AUTO_INCREMENT,
    issue_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    action VARCHAR(40) NOT NULL,
    result VARCHAR(20) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    error_code VARCHAR(80) NULL,
    occurred_at_utc DATETIME(6) NOT NULL,
    pg_snapshot_json LONGTEXT NULL,
    before_order_status VARCHAR(30) NULL,
    before_payment_status VARCHAR(30) NULL,
    after_order_status VARCHAR(30) NULL,
    after_payment_status VARCHAR(30) NULL,
    sold_before INT NULL,
    sold_after INT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_payment_recon_action_issue FOREIGN KEY (issue_id) REFERENCES payment_reconciliation_issue (id)
);

CREATE TABLE payment_reconciliation_progress (
    id TINYINT NOT NULL,
    coverage_start_utc DATETIME(6) NOT NULL,
    completed_through_utc DATETIME(6) NOT NULL,
    updated_at_utc DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE `payments` (
                            `id` bigint NOT NULL AUTO_INCREMENT,
                            `amount` bigint NOT NULL,
                            `approved_at` datetime(6) DEFAULT NULL,
                            `method` varchar(255) DEFAULT NULL,
                            `payment_key` varchar(200) NOT NULL,
                            `receipt_url` varchar(500) DEFAULT NULL,
                            `status` varchar(30) NOT NULL,
                            `order_id` bigint NOT NULL,
                            PRIMARY KEY (`id`),
                            UNIQUE KEY `UK35yqdahtiysne6iij9ske72bj` (`payment_key`),
                            UNIQUE KEY `UK8vo36cen604as7etdfwmyjsxt` (`order_id`),
                            CONSTRAINT `FK81gagumt0r8y3rmudcgpbk42l`
                                FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
