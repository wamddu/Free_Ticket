# 마지막 1장에 100명이 동시에 주문한 문제와 해결

> 작성일 당시의 기록이다. 본문에 나오는 측정 스크립트·원시 결과 파일은 용량 때문에 저장소에 포함하지 않았다. 코드 위치와 수치는 작성 시점 기준이다.

작성일: 2026-09-25

## 요약

남은 티켓이 1장인 상태에서 사용자 100명이 동시에 1장씩 주문을 생성했다. 수정 전에는 **100명 모두 주문 생성에 성공**했고 DB에도 주문 100건이 저장됐다. 티켓 행에 비관적 잠금이 이미 있었지만, 잠금을 얻기 전에 실행한 일반 조회가 MySQL의 읽기 시점을 고정하여 잠금 뒤의 예약 수량 계산에 이전 데이터가 사용됐다.

`OrderService.createOrder()`에서 티켓 잠금 조회를 트랜잭션의 첫 DB 조회로 옮겼다. 같은 MySQL 테스트를 다시 실행한 결과 **주문 성공 1건, `TICKET_SOLD_OUT` 99건, 저장된 주문 1건**이 확인됐다.

## 주문 생성 시 수량을 계산하는 방식

주문 API는 [`OrderService.createOrder()`](../../code/backend/src/main/java/wamddu/backend/order/service/OrderService.java)에서 하나의 트랜잭션으로 실행된다. [`TicketRepository.findByIdForUpdate()`](../../code/backend/src/main/java/wamddu/backend/ticket/repository/TicketRepository.java)는 `PESSIMISTIC_WRITE` 잠금으로 티켓을 조회한다. 이어서 [`OrderRepository.sumActiveQuantity()`](../../code/backend/src/main/java/wamddu/backend/order/repository/OrderRepository.java)가 유효한 미결제 주문의 수량을 합산한다.

```text
주문 가능한 수량 = 전체 티켓 수량 - 판매 완료 수량 - 유효한 미결제 주문 수량
```

합산 대상은 만료 전 `PENDING` 주문과, 만료 시각과 관계없이 유지해야 하는 `CONFIRMING`·`CANCELING` 주문이다. 주문 가능 수량보다 요청 수량이 크면 `409 TICKET_SOLD_OUT`을 반환한다. 주문에 성공하면 `PENDING` 주문을 저장하며, 이 단계에서 `sold_ticket`은 증가하지 않는다. 결제가 완료되어야 판매 수량이 증가한다.

## 문제 재현

[`LastTicketConcurrencyTest`](../../code/backend/src/test/java/wamddu/backend/order/service/LastTicketConcurrencyTest.java)는 별도의 MySQL 8.0 DB에 전체 수량 1장, 판매 수량 0장인 티켓과 서로 다른 사용자 100명을 만든다. 100개 스레드를 준비한 뒤 시작 신호를 동시에 보내 각 사용자가 같은 티켓을 1장씩 `createOrder()`로 요청한다. 테스트는 응답 코드별 건수, DB에 저장된 주문 수, 판매 수량, DB 격리 수준을 기록한다.

이는 실제 서비스와 트랜잭션·MySQL을 사용하는 **주문 생성 통합 테스트**다. HTTP 요청, 인증, 외부 결제 승인까지 실행하는 부하 테스트는 아니다.

| 항목 | 수정 전 결과 |
| --- | ---: |
| DB 격리 수준 | `REPEATABLE-READ` |
| 주문 생성 성공 | 100건 |
| `TICKET_SOLD_OUT` | 0건 |
| DB에 저장된 주문 | 100건 |
| 판매 완료 수량 | 0장 |
| 기대값 `성공 1건, 매진 99건` 검사 | 실패 |

실제 출력은 다음과 같았다.

```text
100 buyers, 1 ticket: isolation=REPEATABLE-READ, results={CREATED=100}, savedOrders=100, soldTickets=0
expected: 1L
 but was: 100L
```

`soldTickets=0`은 초과 판매가 없었다는 검증 결과가 아니다. 이 테스트에서는 결제를 시도하지 않아 어떤 주문도 판매 완료 상태로 바뀌지 않았다는 뜻이다. 확인된 결함은 **재고 1장에 대해 유효한 미결제 주문을 여러 건 만들 수 있었다는 것**이다.

## 행 잠금이 있었는데도 실패한 이유

수정 전 조회 순서는 다음과 같았다.

```text
트랜잭션 시작
→ 사용자 일반 조회
→ 티켓 잠금 조회
→ 유효한 미결제 주문 수량 일반 조회
→ 주문 저장 및 커밋
```

MySQL InnoDB의 기본 격리 수준인 `REPEATABLE READ`에서는 트랜잭션의 첫 일반 조회가 읽기 시점을 만든다. 같은 트랜잭션의 이후 일반 조회는 그 시점의 데이터를 계속 본다. 반면 `FOR UPDATE` 같은 잠금 조회는 최신 상태의 행을 읽고 잠근다. 잠금 조회가 앞서 만들어진 일반 조회의 읽기 시점을 새로 만들지는 않는다. [MySQL 트랜잭션 격리 수준 문서](https://dev.mysql.com/doc/refman/8.0/en/innodb-transaction-isolation-levels.html), [일반 조회와 읽기 시점 문서](https://dev.mysql.com/doc/refman/8.0/en/innodb-consistent-read.html)

두 요청만 예로 들면 아래 순서가 가능하다.

1. A와 B가 각각 주문 트랜잭션을 시작하고 사용자 정보를 일반 조회한다. 두 트랜잭션의 읽기 시점에는 아직 미결제 주문이 없다.
2. A가 티켓 행 잠금을 얻는다. B는 같은 행의 잠금 조회에서 기다린다.
3. A가 미결제 수량 0을 확인하고 주문 1건을 저장한 뒤 커밋한다.
4. B가 잠금을 얻지만, 미결제 수량을 일반 조회할 때는 **1번에서 만든 읽기 시점**을 사용한다. A의 주문이 보이지 않아 B도 주문을 생성한다.

따라서 잠금 자체가 없었던 것이 아니라, **잠금 이후의 합계 조회가 잠금 이전 시점의 데이터를 사용한 것**이 문제였다.

## 검토한 해결책

| 방법 | 장점 | 단점과 이번 선택 여부 |
| --- | --- | --- |
| **티켓 잠금 조회를 첫 DB 조회로 이동** | 기존 티켓 행 잠금과 수량 계산을 재사용한다. 스키마·주문 상태·결제 흐름을 바꾸지 않는다. | 같은 티켓 주문은 순서대로 처리되어 잠금 대기가 생긴다. 앞으로 잠금보다 앞에 일반 조회를 넣지 않아야 한다. **이번에 선택했다.** |
| **주문 생성 트랜잭션만 `READ COMMITTED`로 변경하고 티켓 잠금 유지** | 각 일반 조회가 새 읽기 시점을 사용하므로, 잠금 뒤 합계 조회가 앞선 커밋을 볼 수 있다. | 트랜잭션 격리 의미가 바뀌며 다른 조회에 미치는 영향도 살펴야 한다. 행 잠금은 여전히 필요하다. 현재 구조에서는 격리 수준 변경 없이 더 작은 수정이 가능했다. [MySQL 격리 수준 문서](https://dev.mysql.com/doc/refman/8.0/en/innodb-transaction-isolation-levels.html) |
| **예약 수량 컬럼과 조건부 `UPDATE`** | 수량 확인과 예약 증가를 DB의 한 변경문으로 처리할 수 있고, 주문 합계를 매번 조회하지 않아도 된다. | 예약 만료·취소·결제 완료·복구 때 수량을 정확히 조정해야 한다. 스키마와 여러 상태 전이를 함께 변경해야 하므로 이번 결함 수정 범위를 넘는다. |
| **티켓의 낙관적 잠금(`@Version`)** | 경합이 낮을 때 긴 행 잠금 대기를 줄일 수 있다. | 버전 필드만 추가해서는 효과가 없다. 주문 생성 때 티켓의 버전을 실제로 변경하고 충돌 시 재시도 또는 매진 처리를 해야 한다. 마지막 1장에 100명이 몰리는 상황에서는 충돌이 많다. |
| **`SERIALIZABLE` 격리 수준** | DB가 일반 조회에도 더 강한 잠금 규칙을 적용한다. | 잠금 범위·대기·교착 상태가 증가할 수 있고 재시도 정책이 필요하다. 한 메서드의 조회 순서 문제에 적용하기에는 영향이 크다. [MySQL 격리 수준 문서](https://dev.mysql.com/doc/refman/8.0/en/innodb-transaction-isolation-levels.html) |
| **Redis 등의 분산 잠금** | 애플리케이션이 DB 진입 전 요청을 직렬화할 수 있다. | 별도 인프라와 잠금 만료·장애·소유권 처리가 필요하다. DB의 재고 검증을 대신할 수 없으며, 이미 공유 DB의 행 잠금이 있으므로 이 문제에는 추가 이득이 작다. |

이번에는 **이미 존재하는 티켓 행 잠금을 올바른 시점에 사용하는 방법**을 선택했다. 결함의 원인이 첫 일반 조회의 위치로 좁혀졌고, 변경이 조회 두 건의 순서를 바꾸는 데 그치기 때문이다. 별도 예약 카운터나 잠금 서비스를 도입하면 주문 만료와 결제 복구까지 함께 수정해야 한다.

## 적용한 변경과 동작

[`OrderService.createOrder()`](../../code/backend/src/main/java/wamddu/backend/order/service/OrderService.java)의 첫 두 조회 순서를 바꿨다.

```text
수정 전: 사용자 일반 조회 → 티켓 잠금 조회 → 미결제 주문 합계 조회
수정 후: 티켓 잠금 조회 → 사용자 일반 조회 → 미결제 주문 합계 조회
```

수정 후에는 같은 티켓의 잠금 조회가 순서대로 진행된다. 먼저 잠금을 얻은 트랜잭션이 주문을 커밋하거나 롤백할 때까지 다음 잠금 조회가 기다린다. 다음 트랜잭션의 **첫 일반 조회는 잠금을 얻은 뒤** 실행되므로, 그 이후 미결제 주문 합계에는 앞서 커밋된 주문이 반영된다. 첫 주문이 정상적으로 저장됐다면 나머지는 `TICKET_SOLD_OUT`을 받는다. 첫 주문이 롤백됐다면 뒤따르는 요청이 주문할 수 있다.

잠금은 요청 순서의 공정성을 보장하지 않는다. 가장 먼저 HTTP 요청을 보낸 사람이 아니라 DB 잠금을 먼저 획득한 사람이 먼저 진행한다. 또한 일반 페이지 조회를 모두 막는 것은 아니다. 충돌하는 잠금 조회·수정이 해당 잠금의 해제를 기다린다.

## 수정 후 검증 결과

수정 전과 **같은** `LastTicketConcurrencyTest`를 새 임시 MySQL 8.0 DB에서 다시 실행했다. 테스트의 기대값은 변경하지 않았다.

| 항목 | 수정 전 | 수정 후 |
| --- | ---: | ---: |
| DB 격리 수준 | `REPEATABLE-READ` | `REPEATABLE-READ` |
| 주문 생성 성공 | 100건 | **1건** |
| `TICKET_SOLD_OUT` | 0건 | **99건** |
| DB에 저장된 주문 | 100건 | **1건** |
| 판매 완료 수량 | 0장 | 0장 |
| 100명 동시 주문 테스트 | 실패 | **통과** |

수정 후 실제 출력:

```text
100 buyers, 1 ticket: isolation=REPEATABLE-READ, results={CREATED=1, TICKET_SOLD_OUT=99}, savedOrders=1, soldTickets=0
BUILD SUCCESSFUL
```

기존 `OrderServiceTest`도 별도로 실행해 통과했다. 이 검증은 **한 MySQL DB에서 100명이 동시에 주문을 생성할 때 미결제 주문이 한 건만 저장되는지**를 확인한다. 결제 승인, 여러 서버에서의 HTTP 요청, 모든 장애·시간 초과 상황을 시험한 결과로 확대해서 해석하지 않는다.

### 다시 실행하는 방법

테스트는 다른 DB 데이터를 건드리지 않도록 전용 임시 DB를 전제로 하며, 포트 `3308`과 테스트 전용 비밀번호를 사용한다. 테스트의 `ddl-auto=create-drop` 설정이 해당 DB의 테이블을 만들고 삭제하므로 **기존 DB를 이 주소에 연결하면 안 된다.** PowerShell에서 실행한다.

```powershell
docker run --rm -d --name free-ticket-concurrency-test `
  -e MYSQL_ROOT_PASSWORD=concurrency-test `
  -e MYSQL_DATABASE=free_ticket_test `
  -p 127.0.0.1:3308:3306 mysql:8.0

# MySQL 초기화가 끝난 뒤 실행
cd code/backend
$env:RUN_MYSQL_CONCURRENCY='true'
.\gradlew.bat test --tests 'wamddu.backend.order.service.LastTicketConcurrencyTest' --console=plain

docker stop free-ticket-concurrency-test
```

`RUN_MYSQL_CONCURRENCY`가 설정되지 않으면 이 MySQL 전용 테스트는 일반 테스트 실행에서 건너뛴다.

## 범위와 남은 사항

- 화면의 [`Ticket.getRemainingTickets()`](../../code/backend/src/main/java/wamddu/backend/ticket/domain/Ticket.java)는 `전체 수량 - 판매 완료 수량`만 표시한다. `PENDING` 같은 예약 수량은 빼지 않으므로 주문이 1건 만들어진 뒤에도 결제 전까지 화면에는 1장이 보일 수 있다. 주문 생성은 잠금 뒤 별도 계산으로 다시 검증하므로, 화면 표시 문제와 중복 주문 방지는 구분된다.
- 이번 테스트는 결제를 수행하지 않았다. `soldTickets=0`은 테스트 범위에 따른 정상 결과이며, 결제 동시성 전체의 검증 수치가 아니다.
- 티켓 잠금을 먼저 얻으므로 존재하지 않는 사용자에 대한 검사도 잠금 조회 뒤에 실행된다. 동일한 티켓에 요청이 몰리면 잠금 대기 시간과 DB 연결 사용량을 관찰해야 한다.
- 이 방식은 해당 티켓의 주문 수량을 바꾸는 다른 경로도 같은 티켓 행 잠금 규칙을 따른다는 전제에 의존한다. 새 주문 생성 경로를 추가할 때 이 규칙을 유지해야 한다.
