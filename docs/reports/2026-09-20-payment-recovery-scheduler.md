# 미완료 결제 스케줄러

> 작성일 당시의 기록이다. 본문에 나오는 측정 스크립트·원시 결과 파일은 용량 때문에 저장소에 포함하지 않았다. 코드 위치와 수치는 작성 시점 기준이다.

## 목적과 범위

승인 응답 유실, 승인 뒤 서버 종료, 완료 트랜잭션의 반복 실패, 취소 응답 유실로 남은 `CONFIRMING`·`CANCELING` 주문을 DB에서 찾아 토스 상태를 조회하고 복구한다. 기존 요청 중 즉시 복구는 유지하고, 같은 완료·취소 판단을 후속 실행에서도 사용한다. Redis나 별도 큐는 사용하지 않는다.

시간이 지났다는 사실만으로 주문을 취소하거나 재고를 해제하지 않는다. 미완료 주문과 수동 확인 대상 주문은 계속 예약 수량에 포함된다. `PENDING` 주문의 10분 만료와 결제 복구 시각은 별개다.

## 저장하는 정보

트랜잭션 A가 `CONFIRMING` 변경과 함께 요청의 `paymentKey`, 첫 `nextRecoveryAt`을 저장한다. 주문번호·총금액·승인 멱등성 키는 기존 주문 필드를 사용한다.

| Order 필드 | 용도 |
| --- | --- |
| `paymentKey` | 서버 재시작 후 토스 결제 조회 및 취소 |
| `nextRecoveryAt` | 다음 복구 가능 시각. 작업을 가져갈 때도 미래 시각으로 변경 |
| `recoveryAttempts` | DB에 커밋된 스케줄러 작업 횟수. 서버 재시작 후에도 유지 |
| `recoveryReviewRequired` | 자동 처리 중단 및 수동 확인 대상 표시 |

`recoveryAttempts`에는 최초 승인 요청과 요청 중 즉시 재시도 횟수는 포함하지 않는다. 작업을 가져온 뒤 서버가 종료되어 외부 조회를 실행하지 못해도 그 작업은 1회로 계산한다. `PAID`나 확정 종료 상태가 되면 다음 복구 시각과 수동 확인 표시를 해제한다.

## 실행 순서

1. `PaymentRecoveryScheduler`가 미완료·수동 확인 제외·확인 시각 도래 조건으로 최대 20건의 주문 PK를 조회한다.
2. 주문별 새 트랜잭션에서 주문 행을 잠그고 동일 조건을 다시 확인한다. 결제 키가 없거나 미완료 주문에 Payment가 이미 있으면 수동 확인으로 전환한다.
3. 복구 횟수를 증가시키고 다음 확인 시각을 미래로 옮겨 커밋한다. 이 커밋이 실패하면 토스를 호출하지 않는다.
4. DB 트랜잭션 밖에서 `GET /v1/payments/{paymentKey}`를 호출한다. 조회 결과의 결제 키·주문번호·금액을 저장된 요청과 비교한다.
5. 새 트랜잭션에서 주문을 다시 잠그고 최신 상태와 Payment를 확인한 뒤 아래 표대로 처리한다.
6. 한 주문이 실패해도 나머지 주문을 계속 처리한다. 자동 복구 횟수가 한도에 도달하면 `recoveryReviewRequired=true`를 저장하고 오류 로그를 남긴다.

## 상태별 처리

| DB 상태 | 토스 조회 상태 | 처리 |
| --- | --- | --- |
| `CONFIRMING`, Payment 없음 | `DONE` | 기존 완료 로직으로 재고·Payment·PAID를 한 번에 반영 |
| `CONFIRMING`, Payment 없음, 티켓 제공 불가 확정 | `DONE` | `CANCELING` 커밋 → 외부 취소 → DB 취소 반영 |
| `CANCELING`, Payment 없음 | `DONE` | 기존 `cancel:{idempotencyKey}`로 취소 재시도 |
| `CONFIRMING` 또는 `CANCELING`, Payment 없음 | `CANCELED` | 추가 취소 호출 없이 DB `CANCELED` 반영 |
| `CONFIRMING`, Payment 없음 | `ABORTED` | `PAYMENT_FAILED` 반영 |
| `CONFIRMING`, Payment 없음 | `EXPIRED` | `EXPIRED` 반영 |
| 조회 중 원래 요청이 완료하여 `PAID`가 됨 | `DONE` | 저장된 결제 키·금액·상태 검증 후 성공 반환, 중복 저장 없음 |
| `CANCELING` | `ABORTED` 또는 `EXPIRED` | 이전 승인·취소 의도와 모순되므로 자동 종료하지 않음 |
| 그 밖의 상태, 정보 불일치, 조회 실패 | 불명확 | 재고 유지, 재확인. 한도 도달 시 수동 확인 |

404 응답도 미승인 확정으로 취급하지 않는다. `READY`, `IN_PROGRESS`, `WAITING_FOR_DEPOSIT`, `PARTIAL_CANCELED` 상태를 임의로 취소·완료 처리하지 않는다. 스케줄러는 승인 API를 다시 호출하지 않는다.

토스 조회 API 및 상태 의미는 [공식 API 문서](https://docs.tosspayments.com/reference)를 기준으로 한다. 가상계좌 입금 대기 및 부분 환불을 포함한 별도 상품 흐름은 이번 변경에 포함하지 않는다.

## 중복 실행과 장애

두 서버가 같은 후보를 조회해도 주문 잠금 안에서 다음 확인 시각을 다시 확인한다. 먼저 작업을 가져간 서버가 미래 시각을 커밋하면 다른 서버는 해당 주문을 건너뛴다. 서버가 종료되면 DB에 저장된 시각이 지난 후 다른 실행이 다시 가져갈 수 있다.

이 시간 제한은 영구 잠금이나 외부 호출의 정확히 한 번 실행 보장이 아니다. 오래 멈춘 작업이 뒤늦게 재개되어 겹칠 수 있으므로 **최종 DB 반영 시 행 잠금·상태 확인과 동일 취소 멱등성 키**도 함께 사용한다. DB 완료는 `CONFIRMING`에서만 가능하고, `CANCELING`은 완료 처리로 돌아가지 않는다.

원래 결제 요청보다 스케줄러가 먼저 `PAID`를 반영했어도 원래 요청은 저장된 성공 결과를 반환할 수 있다. 이미 PAID인 주문으로 새 요청이 들어오는 초기 경로에도 동일한 결제 키·금액·Payment 상태 검증을 적용했다.

토스 호출에는 연결 5초·읽기 30초 제한을 적용했다. 이는 개별 네트워크 대기에 대한 제한이며 복구 배치 전체의 실행 시간 보장은 아니다. DB 조회·커밋 장애로 수동 확인 표시를 저장하지 못하면 로그를 남기고 다음 주기에 다시 확인한다. 최종 작업 횟수는 DB에 저장하므로 마지막 시도 중 서버가 종료되어도 다음 실행에서 한도를 확인한다.

## 설정

| 환경 변수 | 기본값 | 의미 |
| --- | --- | --- |
| `PAYMENT_RECOVERY_ENABLED` | `true` | 스케줄러 등록 여부 |
| `PAYMENT_RECOVERY_DELAY_MS` | `30000` | 시작 대기 및 배치 종료 후 다음 실행까지 대기 |
| `PAYMENT_RECOVERY_RETRY_DELAY_MS` | `120000` | 첫 확인 유예 및 주문별 재확인 간격 |
| `PAYMENT_RECOVERY_MAX_ATTEMPTS` | `20` | 주문별 자동 작업 횟수 한도 |

시간과 횟수에는 양수를 사용한다. 재확인 간격은 정상 외부 호출이 끝날 시간을 충분히 고려해 설정한다. 기본값의 2분은 주문 만료 시간이 아니며 정확히 2분 뒤 처리된다는 뜻도 아니다. 순차 배치이므로 처리 지연과 서버 중단에 따라 실제 재시도 시각은 늦어질 수 있다.

테스트 환경은 백그라운드 스케줄러를 비활성화하고, 통합 테스트에서 같은 실행 메서드를 직접 호출한다. 시스템 시각을 기다리는 대신 DB의 다음 실행 시각을 조정한다.

## 기존 데이터와 운영 확인

신규 복구 필드가 추가된다. 2026-09-21에 성능용 인덱스 선언은 엔티티·백업 SQL·백업 코드에서 제거했다. PK·UNIQUE·외래키 제약은 유지하며, MySQL은 외래키 유지에 필요한 인덱스가 없으면 자동 생성한다([MySQL 공식 문서](https://dev.mysql.com/doc/refman/8.0/en/create-table-foreign-keys.html)). 기존 환경의 스키마 변경 도구로 적용해야 할 MySQL 컬럼은 다음과 같다.

```sql
-- 아직 해당 컬럼이 없는 스키마에 적용하는 예시.
ALTER TABLE orders
    ADD COLUMN payment_key VARCHAR(200) NULL,
    ADD COLUMN next_recovery_at DATETIME(6) NULL,
    ADD COLUMN recovery_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN recovery_review_required BOOLEAN NOT NULL DEFAULT FALSE;

```

현재 프로젝트의 `ddl-auto=update`가 먼저 적용한 환경에는 같은 DDL을 중복 실행하지 않는다. `orders.status`가 ENUM/CHECK 제약을 사용한다면 이전 변경에서 추가한 `CANCELING`도 허용돼 있어야 한다. H2 테스트는 실제 운영 MySQL 마이그레이션 검증을 대신하지 않는다.

인덱스 선언을 제거해도 이미 생성된 DB 인덱스가 자동 삭제되는 것은 아니다. 2026-09-21 접속 정보를 수정한 뒤 로컬 `free_ticket` DB의 인덱스와 외래키를 조회했다. 성능용 인덱스는 이미 없었으며, PK·UNIQUE와 외래키 유지에 필요한 인덱스만 남아 있어 추가 DROP은 실행하지 않았다. `idx_order_recovery`와 `idx_ticket_price_history_ticket_changed_at`도 없는 것을 확인했다. 성능용 인덱스 없이 데이터가 많아지면 복구 후보 조회 비용이 증가할 수 있다.

예전 `CONFIRMING`·`CANCELING` 주문에 결제 키가 없으면 추측하여 복구하지 않고 수동 확인 표시를 남긴다. 운영자는 다음과 같이 대상을 찾아 토스 내역과 대조할 수 있다.

```sql
SELECT order_id, status, total_amount, recovery_attempts, next_recovery_at
FROM orders
WHERE recovery_review_required = TRUE;
```

자동 복구를 다시 허용하려면 먼저 외부 결제 상태와 DB 불일치 원인을 확인해야 한다. 상태만 PENDING으로 되돌리거나 재고부터 해제하면 안 된다. 관리 화면·알림 채널·수동 복구 API는 이번에 추가하지 않았다. PAID 이후 결제사 관리자에서 별도로 취소한 거래를 찾는 전체 결제 대조 작업도 범위 밖이다.

## 검증

기존 문제 1·2 재현 테스트는 수정하지 않았다. `PaymentRecoverySchedulerIntegrationTest`는 실제 Spring 트랜잭션과 H2를 사용하고 토스만 대체한다. Repository spy는 두 작업이 같은 후보를 읽도록 동기화하거나 커밋 장애를 주입할 때 사용한다.

- 승인 응답 유실 후 DB에 결제 키·다음 실행 시각이 남는지 검증.
- 새 PaymentService·스케줄러 인스턴스가 기존 DB만으로 복구하는지 검증. 실제 JVM 강제 종료·재기동 테스트는 아니다.
- 두 작업자가 같은 후보를 동시에 읽어도 결제 기록·판매 수량이 한 번만 반영되는지 검증.
- 원래 승인 응답이 지연되는 동안 복구가 먼저 완료돼도 중복 반영이 없는지 검증.
- 취소 중 중단, 취소 응답 유실, DB 커밋 실패 후 다음 실행의 복구 검증.
- 확정 실패·취소와 미확정 상태를 구분하고, 조회 실패 시 재고를 유지하는지 검증.
- 한도 초과·결제 키 누락·미완료 주문에 Payment 존재 시 수동 확인 처리 검증.
- 토스 조회 URL·인증 헤더·응답 검증·404 실패 처리는 로컬 HTTP 서버로 검증.

구현 전 조회·스케줄러 동작을 비운 상태의 테스트 실행은 26개 중 19개 실패했다. DB 복구 정보 저장은 그 이전에 실패를 확인하고 반영했다. 최종 실행 결과는 아래에 기록한다.

2026-09-20 최종 검증: `code/backend`에서 `./gradlew.bat test --no-daemon` 실행, **전체 94개 통과, 실패 0개, 오류 0개, 건너뜀 0개**. 스케줄러 통합 테스트는 22개이며 기존 `PaymentExpiryIntegrationTest`와 `PaymentCommitFailureIntegrationTest`도 수정 없이 통과했다. 테스트 보고서는 `code/backend/build/reports/tests/test/index.html`에서 확인할 수 있다.
