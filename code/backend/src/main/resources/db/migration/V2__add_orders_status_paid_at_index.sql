-- 주간 랭킹은 결제 완료 시각(paid_at) 범위와 주문 상태로 주문을 고른다.
CREATE INDEX idx_orders_status_paid_at ON orders (status, paid_at);
