-- 인기 공연의 예매 오픈에 대기열을 켠다. 켜진 공연은 대기열 입장권이 있어야 주문할 수 있다.
ALTER TABLE events ADD COLUMN queue_enabled BOOLEAN NOT NULL DEFAULT FALSE;
