# orders.ticket_id 인덱스 전후 성능 비교

> 작성일 당시의 기록이다. 본문에 나오는 측정 스크립트·원시 결과 파일은 용량 때문에 저장소에 포함하지 않았다. 코드 위치와 수치는 작성 시점 기준이다.

## 변경 및 실험 조건

- 변경: `orders(ticket_id)` 단일 비고유 인덱스 `idx_orders_ticket_id`. JPA 선언, backup.sql, 기존 DB용 ALTER SQL에 반영.
- 실제 로컬 DB와 AWS는 변경하지 않음. 기존 격리 테스트 DB에서만 인덱스 생성.
- 동일 애플리케이션 이미지 `free-ticket-perf:7f451dd`를 사용하고 DB 인덱스 유무만 전환. 수정된 Java 선언은 별도로 기존 테스트 66건 통과.
- 앱과 MySQL 각각 CPU 0.5개, 메모리 768MiB. MySQL 버퍼 풀 128MiB, SQL 제한 8초, HTTP 제한 30초.
- 매 wave 전 사용자 2,500 / 행사 3,500 / 티켓 30,500 / 주문 100,500행으로 fixture 재설정. 주문은 사용자마다 서로 다른 티켓으로 생성.
- 50 / 100 / 500명이 각 1회 동시에 요청. 검색·주문은 전후 각 3회, 챗봇은 각 1회. 지속 부하의 처리 용량 시험이 아님.
- 표의 P95는 각 wave의 P95를 구한 뒤 3회 중앙값으로 집계. 전체 응답에는 실패 및 타임아웃이 포함되므로 성공률과 함께 해석.
- 전(before) 블록 이후 후(after) 블록 순서. 외부 네트워크 서비스 대신 챗봇은 20ms 고정 지연 로컬 Gemini stub을 사용.
- 서버 시작 시 인기 행사 API로 준비 상태 확인. 시나리오별 재시작과 직접 API 단일 요청 warmup 수행. 오류·타임아웃 및 챗봇 wave 후에는 서버를 재시작하므로 완전히 동일한 JVM/cache 온도를 보장하지 않음.

## 직접 관련 API 결과

| API | 동시 요청 | 성공 전 → 후 | 전체 P95 전 → 후 (초) | 성공 응답 P95 전 → 후 (초) |
|---|---:|---|---:|---:|
| 예매 가능 검색 | 50 | 0/150 (0.0%) → 150/150 (100.0%) | 30.362 → 12.613 | - → 12.613 |
| 예매 가능 검색 | 100 | 0/300 (0.0%) → 300/300 (100.0%) | 30.359 → 22.622 | - → 22.622 |
| 예매 가능 검색 | 500 | 0/1500 (0.0%) → 390/1500 (26.0%) | 31.001 → 30.471 | - → 28.899 |
| 주문 생성 | 50 | 150/150 (100.0%) → 150/150 (100.0%) | 4.901 → 0.803 | 4.901 → 0.803 |
| 주문 생성 | 100 | 300/300 (100.0%) → 300/300 (100.0%) | 6.220 → 1.727 | 6.220 → 1.727 |
| 주문 생성 | 500 | 1439/1500 (95.9%) → 1500/1500 (100.0%) | 30.090 → 7.381 | 28.824 → 7.381 |

검색: `GET /api/events?availableOnly=true&maxPrice=50000`
주문: `POST /api/orders` (`ticketId`는 요청마다 다름, quantity=1)

성공 응답 P95는 성공한 요청만의 값이다. 성공률이 낮은 상태에서 빠른 성공 응답만으로 성능을 판단하면 안 된다. 성공 0건은 `-`로 표시했다.

### 반복 변동 폭

| API | 동시 요청 | 전 P95 최소~최대 (초) | 후 P95 최소~최대 (초) |
|---|---:|---:|---:|
| available | 50 | 30.132~30.610 | 11.801~14.077 |
| available | 100 | 30.137~30.965 | 21.823~22.699 |
| available | 500 | 30.832~31.005 | 30.450~30.599 |
| create-order | 50 | 2.952~4.957 | 0.560~1.598 |
| create-order | 100 | 5.936~6.234 | 1.088~3.171 |
| create-order | 500 | 29.798~30.132 | 5.351~11.468 |

## 챗봇의 예매 가능 검색 분기

같은 검색 조건(availableOnly=true, maxPrice=50000)을 반환하는 로컬 Gemini stub으로 확인했다. 실제 모델 응답 품질·외부 지연을 측정한 값은 아니다. 서버의 분당 10건 한도를 유지하고 각 wave 전에 한도를 초기화했다.

| 동시 요청 | 성공 전 → 후 | 429 전 → 후 | 성공 P95 전 → 후 (초) |
|---:|---|---:|---:|
| 50 | 0/50 (0.0%) → 10/50 (20.0%) | 40 → 40 | - → 4.514 |
| 100 | 0/100 (0.0%) → 10/100 (10.0%) | 90 → 90 | - → 4.562 |
| 500 | 0/500 (0.0%) → 10/500 (2.0%) | 490 → 490 | - → 8.714 |

429 응답을 검색 성공으로 계산하지 않았다. 전체 P95는 빠른 429에 의해 작아질 수 있으므로 챗봇의 비교 지표로 사용하지 않는다.

## SQL 실행 계획과 정확성

아래는 동일 티켓(200001)의 예약 수량 합계 SQL에 대한 EXPLAIN ANALYZE 실제 실행 결과다. 한 번의 진단 실행으로, 반복 평균이나 부하 중 SQL 시간은 아니다.

### 적용 전
```text
-> Aggregate: sum(o.quantity)  (cost=11047 rows=1) (actual time=56.7..56.7 rows=1 loops=1)
    -> Filter: ((o.ticket_id = 200001) and (o.`status` in ('PENDING','CONFIRMING','CANCELING')) and ((o.`status` <> 'CANCELING') or exists(select #2) is false) and ((o.expires_at > TIMESTAMP'2026-10-31 12:00:00') or (o.`status` in ('CONFIRMING','CANCELING'))))  (cost=10822 rows=2241) (actual time=56.5..56.6 rows=1 loops=1)
        -> Table scan on o  (cost=10822 rows=99837) (actual time=0.29..53.4 rows=100500 loops=1)
        -> Select #2 (subquery in condition; dependent)
            -> Limit: 1 row(s)  (cost=0.35 rows=1) (never executed)
                -> Single-row covering index lookup on p using UK8vo36cen604as7etdfwmyjsxt (order_id=o.id)  (cost=0.35 rows=1) (never executed)
```

### 적용 후
```text
-> Aggregate: sum(o.quantity)  (cost=0.295 rows=1) (actual time=0.0842..0.0843 rows=1 loops=1)
    -> Filter: ((o.`status` in ('PENDING','CONFIRMING','CANCELING')) and ((o.`status` <> 'CANCELING') or exists(select #2) is false) and ((o.expires_at > TIMESTAMP'2026-10-31 12:00:00') or (o.`status` in ('CONFIRMING','CANCELING'))))  (cost=0.272 rows=0.224) (actual time=0.0776..0.0796 rows=1 loops=1)
        -> Index lookup on o using idx_orders_ticket_id (ticket_id=200001)  (cost=0.272 rows=1) (actual time=0.0243..0.0262 rows=1 loops=1)
        -> Select #2 (subquery in condition; dependent)
            -> Limit: 1 row(s)  (cost=0.35 rows=1) (never executed)
                -> Single-row covering index lookup on p using UK8vo36cen604as7etdfwmyjsxt (order_id=o.id)  (cost=0.35 rows=1) (never executed)
```

검색 전체 쿼리의 EXPLAIN JSON도 `results/before-available-explain.json`, `results/after-available-explain.json`에 보관했다. JSON의 rows_examined_per_scan은 추정치이며 위 ANALYZE의 실제 행 수와 구분해야 한다.

5개 행사로 제한한 검색 결과와 티켓 예약 합계가 전후 동일한지 비교했다. 전 상태의 전체 검색은 시간 제한을 초과하므로 전체 결과의 전후 동등성 검증으로 확대 해석하지 않는다.

### 인덱스 크기 (InnoDB 통계 페이지 수)

```text
FK32ql8ubntj5uh44ph9659tiih	225
PRIMARY	865
UKhmsk25beh6atojvle1xuymjj0	288
idx_orders_ticket_id	161
uk_orders_idempotency_key	480
```

페이지 수는 InnoDB 통계 기준이며 실제 디스크 파일 증분과 다를 수 있다. 신규 인덱스는 INSERT/UPDATE/DELETE 때 추가 유지 비용이 발생한다. 이번 주문 생성 시험에는 INSERT가 포함되지만 순수 쓰기 비용은 분리 측정하지 않았다.

## 적용 방법과 범위

- 신규 DB: 수정된 `code/backend/backup.sql`로 생성.
- 기존 DB: `code/backend/db/2026-10-31-orders-ticket-index.sql`을 해당 DB에 한 번 적용. 이미 동일 인덱스가 있으면 중복 실행하지 않는다.
- 배포는 Hibernate `ddl-auto=validate`이므로 엔티티 변경만 배포해도 기존 DB 인덱스가 자동 생성되지는 않는다.
- SQL은 INPLACE / LOCK=NONE으로 격리 MySQL 8에서 실행 검증했다. 운영에서는 시작·종료 시 메타데이터 잠금 대기가 발생할 수 있으므로 적용 시점을 선택한다.
- 자동 가격 조정 스케줄러도 해당 집계 쿼리를 쓰지만 이번 API 부하 시험에서는 비활성화했다. 예약 목록, 가격 설정 API 등 다른 쿼리의 개선으로 일반화하지 않는다.

## 산출물

- `run_index.py`: 고정 조건으로 측정하는 실행 스크립트(인덱스 없는 초기 상태 필요).
- `summary.json`, `summary.csv`: 수치 집계.
- `results/*-r*.json`: 42 wave의 요청별 상태·지연·자원 통계.
- `results/*-explain.json`, `*-analyze.txt`: 실행 계획.
- `results/*-correctness.tsv`: 검색/집계 결과 일치 확인.

42 wave / 9,100개 측정 요청. warmup 및 준비 상태 요청은 제외.

## 정량적 해석

- 검색 50명: 성공 0/150 → 150/150. 적용 전 성공 응답이 없으므로 정상 응답의 '몇 배 빨라짐'은 계산할 수 없다. 적용 후 성공 응답 P95 중앙값은 12.613초.
- 검색 100명: 성공 0/300 → 300/300. 적용 전 성공 응답이 없으므로 정상 응답의 '몇 배 빨라짐'은 계산할 수 없다. 적용 후 성공 응답 P95 중앙값은 22.622초.
- 검색 500명: 성공 0/1500 → 390/1500. 적용 전 성공 응답이 없으므로 정상 응답의 '몇 배 빨라짐'은 계산할 수 없다. 적용 후 성공 응답 P95 중앙값은 28.899초.
- 주문 50명: 전체 P95 4.901초 → 0.803초, 관측 감소율 83.6%. 성공률 100.00% → 100.00%.
- 주문 100명: 전체 P95 6.220초 → 1.727초, 관측 감소율 72.2%. 성공률 100.00% → 100.00%.
- 주문 500명: 전체 P95 30.090초 → 7.381초, 관측 감소율 75.5%. 성공률 95.93% → 100.00%.
- 인덱스 적용으로 주문 탐색이 전체 스캔에서 ticket_id 인덱스 조회로 바뀐 것을 실행 계획에서 확인했다. 전체 검색의 orders 접근 방식은 ALL → ref, 회당 추정 읽기 행 수는 99,837 → 3이다.
- 단일 티켓 집계 실제 읽기 행 수는 100,500 → 1, 진단 실행 시간은 56.7ms → 0.0843ms였다. 이 한 번의 SQL 수치를 API 전체 속도 배수로 해석하지 않는다.
- 500명 검색은 적용 후에도 시간 초과가 남는다. 이 API는 전체 3,500개 행사를 한 응답으로 반환한다. 다음 개선 실험은 페이지네이션과 검색 SQL의 반복 집계 비용을 분리 측정하는 것이 합리적이다.
- Docker Desktop의 공유 호스트 및 3회 반복 결과다. JVM 워밍업, 캐시, 실행 순서 영향을 완전히 분리하지 않았으므로 운영 환경의 동일한 개선율이나 통계적 유의성을 보장하지 않는다.

### 워밍업 영향 확인

적용 전 주문 500명 wave에서 시간 초과가 발생해 서버가 재시작되었고, 적용 후에는 재시작 없이 다음 반복으로 이어졌다. 따라서 3회 중앙값의 감소율에는 인덱스 외에 JVM 예열 차이가 포함될 수 있다. 동일한 시나리오 시작 재시작·단일 요청 warmup·50→100→500 순서를 따른 첫 회차끼리도 비교했다.

| 동시 요청 | 첫 회차 P95 전 → 후 (초) | 관측 감소율 |
|---:|---:|---:|
| 50 | 2.952 → 1.598 | 45.9% |
| 100 | 6.234 → 3.171 | 49.1% |
| 500 | 30.090 → 11.468 | 61.9% |

따라서 주문 500명의 반복 중앙값 관측치는 75.5% 감소이며, 초기 진행 조건을 맞춘 첫 회차에서는 61.9% 감소했다. 첫 회차도 한 표본이므로 정확한 순수 인덱스 효과의 추정치나 신뢰구간으로 보지는 않는다.
