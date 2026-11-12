package wamddu.backend.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import wamddu.backend.global.exception.ApiException;
import wamddu.backend.order.domain.Order;
import wamddu.backend.order.domain.OrderStatus;
import wamddu.backend.order.repository.OrderRepository;
import wamddu.backend.payment.client.TossPaymentsClient;
import wamddu.backend.payment.domain.Payment;
import wamddu.backend.payment.dto.request.ConfirmPaymentRequest;
import wamddu.backend.payment.dto.response.PaymentResponse;
import wamddu.backend.payment.repository.PaymentRepository;
import wamddu.backend.ticket.domain.Ticket;
import wamddu.backend.ticket.repository.TicketRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final TicketRepository ticketRepository;
    private final TossPaymentsClient tossPaymentsClient;
    private final PlatformTransactionManager transactionManager;

    private static final int MISSING_PAYMENT_MIN_LOOKUPS = 3;
    private static final Duration MISSING_PAYMENT_GRACE = Duration.ofMinutes(10);

    @Value("${payment.recovery.retry-delay-ms:120000}")
    private long recoveryRetryDelayMs = 120000;

    @Value("${payment.recovery.max-attempts:20}")
    private int recoveryMaxAttempts = 20;

    public PaymentResponse cancelReservation(Long userId, String orderId) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        RecoveryAttempt attempt = transaction.execute(status -> {
            Order order = orderRepository.findByOrderIdAndUserIdForUpdate(orderId, userId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다."));
            if (order.getStatus() == OrderStatus.CANCELING) {
                throw cancellationPending(null);
            }
            if (order.getStatus() != OrderStatus.PAID && order.getStatus() != OrderStatus.CANCELED) {
                throw new ApiException(HttpStatus.CONFLICT, "INVALID_ORDER_STATUS", "취소할 수 있는 예매가 아닙니다.");
            }
            Payment payment = paymentRepository.findByOrderOrderIdForUpdate(orderId).orElse(null);
            ConfirmPaymentRequest request = cancellationRequest(order, payment);
            if (order.getStatus() == OrderStatus.CANCELED) {
                return new RecoveryAttempt(userId, request, 0, null);
            }
            Ticket ticket = ticketRepository.findByIdForUpdate(order.getTicket_id()).orElse(null);
            validateCancellationInventory(order, ticket);
            if (ticket.getStart_time() != null && !LocalDateTime.now().isBefore(ticket.getStart_time())) {
                throw new ApiException(HttpStatus.CONFLICT, "CANCELLATION_CLOSED", "이미 시작된 공연은 예매를 취소할 수 없습니다.");
            }
            if ("가상계좌".equals(payment.getMethod())) {
                throw new ApiException(HttpStatus.CONFLICT, "UNSUPPORTED_REFUND_METHOD", "가상계좌 환불은 환불 계좌 확인이 필요합니다. 주문번호로 문의해 주세요.");
            }
            // 취소 의도를 먼저 커밋하고, 재고는 전액 취소가 확인될 때 복구한다.
            order.setStatus(OrderStatus.CANCELING);
            order.setNextRecoveryAt(nextRecoveryAt());
            order.setRecoveryAttempts(0);
            order.setRecoveryReviewRequired(false);
            return new RecoveryAttempt(userId, request, 0, "reservation-cancel:" + order.getIdempotencyKey());
        });
        if (attempt.cancellationKey() == null) {
            return transaction.execute(status -> toResponse(paymentRepository.findByOrderOrderId(orderId).orElseThrow()));
        }
        try {
            return cancelPaidReservation(attempt, transaction);
        } catch (RuntimeException failure) {
            log.warn("예매 취소 결과 확정 실패. 후속 확인 필요. orderId={}", orderId, failure);
            throw cancellationPending(failure);
        }
    }

    private PaymentResponse cancelPaidReservation(RecoveryAttempt attempt, TransactionTemplate transaction) {
        ConfirmPaymentRequest request = attempt.request();
        var pg = tossPaymentsClient.getPayment(request.paymentKey());
        validatePaymentIdentity(request, pg);
        if ("DONE".equals(pg.status())) {
            if (!request.amount().equals(pg.balanceAmount()) || !"KRW".equals(pg.currency())) {
                throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_STATE_MISMATCH", "전액 취소 가능한 결제인지 확인해야 합니다.");
            }
            pg = tossPaymentsClient.cancel(request.paymentKey(), "구매자 예매 취소", attempt.cancellationKey());
            validatePaymentIdentity(request, pg);
        }
        if (!completedFullCancellation(pg)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "INVALID_TOSS_CANCEL_RESPONSE", "완료된 전액 취소를 확인할 수 없습니다.");
        }
        return transaction.execute(status -> {
            Order order = orderRepository.findByOrderIdAndUserIdForUpdate(request.orderId(), attempt.userId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다."));
            Payment payment = paymentRepository.findByOrderOrderIdForUpdate(request.orderId()).orElse(null);
            if (!request.equals(cancellationRequest(order, payment))) {
                throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_STATE_MISMATCH", "저장된 결제 정보가 취소 요청과 일치하지 않습니다.");
            }
            if (order.getStatus() == OrderStatus.CANCELED) {
                return toResponse(payment);
            }
            if (order.getStatus() != OrderStatus.CANCELING) {
                throw new ApiException(HttpStatus.CONFLICT, "INVALID_ORDER_STATUS", "취소 처리 중인 예매가 아닙니다.");
            }
            Ticket ticket = ticketRepository.findByIdForUpdate(order.getTicket_id()).orElse(null);
            validateCancellationInventory(order, ticket);
            ticket.setSold_ticket(ticket.getSold_ticket() - order.getQuantity());
            order.setStatus(OrderStatus.CANCELED);
            order.setNextRecoveryAt(null);
            order.setRecoveryReviewRequired(false);
            payment.setStatus("CANCELED");
            return toResponse(payment);
        });
    }

    private ConfirmPaymentRequest cancellationRequest(Order order, Payment payment) {
        String expectedStatus = order.getStatus() == OrderStatus.CANCELED ? "CANCELED" : "DONE";
        if (payment == null || order.getPaymentKey() == null || order.getPaymentKey().isBlank()
                || !order.getPaymentKey().equals(payment.getPaymentKey())
                || order.getTotalAmount() == null || order.getTotalAmount() <= 0
                || !order.getTotalAmount().equals(payment.getAmount()) || !expectedStatus.equals(payment.getStatus())
                || order.getQuantity() == null || order.getQuantity() <= 0
                || order.getUnitPrice() == null || order.getUnitPrice() <= 0
                || (long) order.getQuantity() * order.getUnitPrice() != order.getTotalAmount()
                || order.getIdempotencyKey() == null || order.getIdempotencyKey().isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_STATE_MISMATCH", "주문과 결제 정보를 확인해야 합니다.");
        }
        return new ConfirmPaymentRequest(order.getPaymentKey(), order.getOrderId(), order.getTotalAmount());
    }

    private void validateCancellationInventory(Order order, Ticket ticket) {
        if (ticket == null || ticket.getEvent() == null || !Objects.equals(ticket.getEvent().getId(), order.getEvent_id())
                || ticket.getSold_ticket() == null || ticket.getTotal_ticket() == null
                || ticket.getSold_ticket() < order.getQuantity() || ticket.getSold_ticket() > ticket.getTotal_ticket()) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TICKET_DATA", "티켓과 판매 수량을 확인해야 합니다.");
        }
    }

    private boolean completedFullCancellation(TossPaymentsClient.TossPaymentResponse pg) {
        if (!"CANCELED".equals(pg.status()) || !"KRW".equals(pg.currency())
                || pg.balanceAmount() == null || pg.balanceAmount() != 0 || pg.cancels() == null || pg.cancels().isEmpty()) {
            return false;
        }
        long total = 0;
        for (var cancel : pg.cancels()) {
            if (cancel == null || cancel.cancelAmount() == null || cancel.cancelAmount() <= 0 || !"DONE".equals(cancel.cancelStatus())) {
                return false;
            }
            try { total = Math.addExact(total, cancel.cancelAmount()); }
            catch (ArithmeticException overflow) { return false; }
        }
        return Objects.equals(total, pg.totalAmount());
    }

    private ApiException cancellationPending(RuntimeException cause) {
        ApiException pending = new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RESERVATION_CANCELLATION_PENDING",
                "예매 취소 결과를 확인 중입니다. 목록을 새로고침해 확인하고, 처리가 계속 지연되면 주문번호로 문의해 주세요.");
        if (cause != null) pending.initCause(cause);
        return pending;
    }

    public PaymentResponse confirm(Long userId, ConfirmPaymentRequest request) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        //트랜잭션 A
        Order preparedOrder = transaction.execute(status -> prepareOrder(userId, request));

        if (preparedOrder.getStatus() == OrderStatus.PAID) {
            return transaction.execute(status -> matchingPaidResponse(preparedOrder, request,
                    paymentRepository.findByOrderOrderId(request.orderId()).orElse(null)));
        }

        //토스 외부 승인 요청
        TossPaymentsClient.TossPaymentResponse toss;
        try {
            toss = tossPaymentsClient.confirm(
                    request.paymentKey(), request.orderId(), request.amount(), preparedOrder.getIdempotencyKey());
        } catch (RestClientException unknownOutcome) {
            // 타임아웃·연결 오류는 승인 여부를 알 수 없다. CONFIRMING으로 남겨 복구 스케줄러가 확정한다.
            log.warn("토스 승인 결과 미확인. 복구 대기. orderId={}", request.orderId(), unknownOutcome);
            throw recoveryPending(unknownOutcome);
        }

        //트랜잭션 B
        try {
            return transaction.execute(status -> completePayment(userId, request, toss));
        } catch (RuntimeException failure) {
            log.warn("결제 완료 처리 실패. DB 상태를 확인합니다. orderId={}", request.orderId(), failure);
            return recoverPayment(userId, request, toss, transaction, failure);
        }
    }

    private PaymentResponse recoverPayment(
            Long userId,
            ConfirmPaymentRequest request,
            TossPaymentsClient.TossPaymentResponse toss,
            TransactionTemplate transaction,
            RuntimeException failure
    ) {
        RecoveryResult result;
        try {
            result = transaction.execute(status -> {
                Order order = orderRepository.findByOrderIdAndUserIdForUpdate(request.orderId(), userId)
                        .orElseThrow(() -> new ApiException(
                                HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다."));
                var payment = paymentRepository.findByOrderOrderId(request.orderId());

                //이미 정상적으로 반영되있음 -> 저장된 결과 반환
                if (order.getStatus() == OrderStatus.PAID) {
                    PaymentResponse response = matchingPaidResponse(order, request, payment.orElse(null));
                    return new RecoveryResult(response, null);
                }

                if (order.getStatus() == OrderStatus.CANCELING && payment.isEmpty()) {
                    validateApproval(order, request, toss);
                    return new RecoveryResult(null, "cancel:" + order.getIdempotencyKey());
                }

                //내부 완료 처리가 반영 X -> 토스 승인 결과를 기준으로 복구
                if (order.getStatus() == OrderStatus.CONFIRMING && payment.isEmpty()) {
                    validateApproval(order, request, toss);
                    Ticket ticket = ticketRepository.findByIdForUpdate(order.getTicket_id()).orElse(null);
                    if (!canFulfill(order, ticket)) {
                        // 취소 의도를 먼저 커밋해야 완료 재시도와 외부 취소가 겹치지 않는다.
                        order.setStatus(OrderStatus.CANCELING);
                        order.setNextRecoveryAt(nextRecoveryAt());
                        return new RecoveryResult(null, "cancel:" + order.getIdempotencyKey());
                    }
                    return new RecoveryResult(applyPayment(order, ticket, toss), null);
                }

                throw recoveryPending(failure);
            });
        } catch (RuntimeException retryFailure) {
            log.warn("결제 복구 미완료. 후속 확인 필요. orderId={}", request.orderId(), retryFailure);
            throw recoveryPending(retryFailure);
        }
        if (result.payment() != null) {
            return result.payment();
        }

        // CANCELING 커밋이 성공한 경우에만 DB 트랜잭션 밖에서 취소한다.
        try {
            var canceled = tossPaymentsClient.cancel(request.paymentKey(), "티켓 제공 불가", result.cancelKey());
            if (canceled == null || !request.paymentKey().equals(canceled.paymentKey())
                    || !request.orderId().equals(canceled.orderId())
                    || !request.amount().equals(canceled.totalAmount()) || !"CANCELED".equals(canceled.status())) {
                throw recoveryPending(failure);
            }
            transaction.executeWithoutResult(status -> finishUnpaidOrder(userId, request, OrderStatus.CANCELED));
        } catch (RuntimeException cancelFailure) {
            log.warn("결제 취소 결과 확정 실패. 후속 확인 필요. orderId={}", request.orderId(), cancelFailure);
            throw recoveryPending(cancelFailure);
        }
        throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_CANCELED", "티켓을 제공할 수 없어 결제를 취소했습니다.");
    }

    private record RecoveryResult(PaymentResponse payment, String cancelKey) {}

    private ApiException recoveryPending(RuntimeException cause) {
        ApiException pending = new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PAYMENT_RECOVERY_PENDING",
                "결제 결과를 확정하지 못했습니다. 다시 결제하지 말고 주문번호로 문의해 주세요."
        );
        pending.initCause(cause);
        return pending;
    }

    private Order prepareOrder(Long userId, ConfirmPaymentRequest request) {
        Order order = orderRepository.findByOrderIdAndUserIdForUpdate(request.orderId(), userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다."));

        if (order.getStatus() == OrderStatus.PAID) {
            return order;
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "INVALID_ORDER_STATUS", "결제를 승인할 수 없는 주문 상태입니다.");
        }
        if (LocalDateTime.now().isAfter(order.getExpiresAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_EXPIRED", "결제 가능 시간이 만료되었습니다.");
        }
        if (!order.getTotalAmount().equals(request.amount())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AMOUNT_MISMATCH", "결제 금액이 주문 금액과 일치하지 않습니다.");
        }
        if (paymentRepository.existsByPaymentKey(request.paymentKey())) {
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_KEY_ALREADY_USED", "이미 사용된 결제 키입니다.");
        }

        order.setStatus(OrderStatus.CONFIRMING);
        order.setPaymentKey(request.paymentKey());
        order.setNextRecoveryAt(nextRecoveryAt());
        return order;
    }

    private PaymentResponse completePayment(Long userId, ConfirmPaymentRequest request,
                                           TossPaymentsClient.TossPaymentResponse toss) {
        Order order = orderRepository.findByOrderIdAndUserIdForUpdate(request.orderId(), userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다."));
        if (order.getStatus() != OrderStatus.CONFIRMING) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_ORDER_STATUS", "결제 처리 중인 주문이 아닙니다.");
        }
        validateApproval(order, request, toss);
        Ticket ticket = ticketRepository.findByIdForUpdate(order.getTicket_id()).orElse(null);
        if (!canFulfill(order, ticket)) {
            throw new ApiException(HttpStatus.CONFLICT, "TICKET_UNAVAILABLE", "티켓을 제공할 수 없습니다.");
        }
        return applyPayment(order, ticket, toss);
    }

    private void validateApproval(Order order, ConfirmPaymentRequest request,
                                  TossPaymentsClient.TossPaymentResponse toss) {
        validatePaymentIdentity(request, toss);
        if (!request.paymentKey().equals(order.getPaymentKey())
                || !request.amount().equals(order.getTotalAmount())
                || !"DONE".equals(toss.status())) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "INVALID_TOSS_RESPONSE", "결제 승인 결과가 주문 정보와 일치하지 않습니다.");
        }
    }

    private void validatePaymentIdentity(ConfirmPaymentRequest request, TossPaymentsClient.TossPaymentResponse toss) {
        if (toss == null || !request.paymentKey().equals(toss.paymentKey())
                || !request.orderId().equals(toss.orderId()) || !request.amount().equals(toss.totalAmount())) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "INVALID_TOSS_RESPONSE", "결제 조회 결과가 주문 정보와 일치하지 않습니다.");
        }
    }

    private boolean canFulfill(Order order, Ticket ticket) {
        if (ticket == null) {
            return false;
        }
        if (order.getQuantity() == null || order.getQuantity() <= 0
                || ticket.getTotal_ticket() == null || ticket.getTotal_ticket() < 0
                || ticket.getSold_ticket() == null || ticket.getSold_ticket() < 0
                || ticket.getSold_ticket() > ticket.getTotal_ticket()) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TICKET_DATA", "티켓 판매 정보가 올바르지 않습니다.");
        }
        return (long) ticket.getSold_ticket() + order.getQuantity() <= ticket.getTotal_ticket();
    }

    private PaymentResponse applyPayment(Order order, Ticket ticket, TossPaymentsClient.TossPaymentResponse toss) {
        ticket.setSold_ticket(ticket.getSold_ticket() + order.getQuantity());
        LocalDateTime approvedAt = LocalDateTime.now();
        order.setStatus(OrderStatus.PAID);
        order.setPaidAt(approvedAt);
        order.setNextRecoveryAt(null);
        order.setRecoveryReviewRequired(false);
        Payment payment = Payment.createPayment(
                order, toss.paymentKey(), toss.totalAmount(), toss.method(), toss.status(), approvedAt,
                toss.receipt() == null ? null : toss.receipt().url());
        return toResponse(paymentRepository.save(payment));
    }

    private PaymentResponse toResponse(Payment payment) {
        return PaymentResponse.from(payment);
    }

    public void recoverPendingPayments() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // 한 번에 20건씩 순서대로 처리한다. 처리량이 부족해지면 잠금과 멱등성을 유지한 채 병렬화를 검토한다.
        var candidates = orderRepository.findRecoveryCandidates(LocalDateTime.now(), PageRequest.of(0, 20));
        for (Long id : candidates) {
            RecoveryAttempt attempt = null;
            try {
                attempt = transaction.execute(status -> claimRecovery(id));
                if (attempt == null) {
                    continue;
                }
                if (attempt.cancellationKey() != null) {
                    cancelPaidReservation(attempt, transaction);
                    continue;
                }
                var toss = tossPaymentsClient.getPayment(attempt.request().paymentKey());
                validatePaymentIdentity(attempt.request(), toss);
                final RecoveryAttempt claimed = attempt;
                if ("DONE".equals(toss.status())) {
                    recoverPayment(attempt.userId(), attempt.request(), toss, transaction,
                            new IllegalStateException("Unfinished payment recovery"));
                } else if ("CANCELED".equals(toss.status())) {
                    transaction.executeWithoutResult(status -> finishUnpaidOrder(
                            claimed.userId(), claimed.request(), OrderStatus.CANCELED));
                } else if ("ABORTED".equals(toss.status()) || "EXPIRED".equals(toss.status())) {
                    OrderStatus terminal = "ABORTED".equals(toss.status()) ? OrderStatus.PAYMENT_FAILED : OrderStatus.EXPIRED;
                    transaction.executeWithoutResult(status -> finishUnpaidOrder(claimed.userId(), claimed.request(), terminal));
                } else {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                            "PAYMENT_STATUS_UNRESOLVED", "결제 상태가 확정되지 않았습니다.");
                }
            } catch (RuntimeException failure) {
                if (failure instanceof ApiException api && "PAYMENT_CANCELED".equals(api.getCode())) {
                    continue; // 취소 API와 DB 반영까지 완료한 결과다.
                }
                if (attempt != null && attempt.cancellationKey() == null && failure instanceof ApiException api
                        && TossPaymentsClient.PAYMENT_NOT_FOUND.equals(api.getCode())
                        && failMissingPayment(id, attempt.number(), transaction)) {
                    continue;
                }
                log.warn("예약된 결제 복구 실패. orderPk={}, orderId={}, attempt={}", id,
                        attempt == null ? null : attempt.request().orderId(),
                        attempt == null ? null : attempt.number(), failure);
                if (attempt != null) {
                    recordRecoveryFailure(id, attempt.number(), transaction);
                }
            }
        }
    }

    private RecoveryAttempt claimRecovery(Long id) {
        Order order = orderRepository.findByIdForUpdate(id).orElse(null);
        if (order == null || !isUnfinished(order) || order.isRecoveryReviewRequired()
                || (order.getNextRecoveryAt() != null && order.getNextRecoveryAt().isAfter(LocalDateTime.now()))) {
            return null;
        }
        var payment = paymentRepository.findByOrderOrderId(order.getOrderId());
        boolean paidCancellation = order.getStatus() == OrderStatus.CANCELING && payment.isPresent();
        if (order.getPaymentKey() == null || order.getPaymentKey().isBlank()
                || order.getRecoveryAttempts() >= recoveryMaxAttempts
                || (payment.isPresent() && !paidCancellation)) {
            order.setRecoveryReviewRequired(true);
            order.setNextRecoveryAt(null);
            log.error("결제 수동 확인 필요. orderId={}", order.getOrderId());
            return null;
        }
        ConfirmPaymentRequest paidCancellationRequest = null;
        if (paidCancellation) {
            try {
                paidCancellationRequest = cancellationRequest(order, payment.orElseThrow());
            } catch (ApiException invalidPayment) {
                order.setRecoveryReviewRequired(true);
                order.setNextRecoveryAt(null);
                log.error("예매 취소 결제 정보 불일치. 수동 확인 필요. orderId={}", order.getOrderId());
                return null;
            }
        }
        order.setRecoveryAttempts(order.getRecoveryAttempts() + 1);
        // 외부 호출 전에 커밋하여 다른 서버가 같은 주문을 즉시 가져가지 못하게 한다.
        order.setNextRecoveryAt(nextRecoveryAt());
        if (paidCancellation) {
            return new RecoveryAttempt(order.getUser().getId(), paidCancellationRequest,
                    order.getRecoveryAttempts(), "reservation-cancel:" + order.getIdempotencyKey());
        }
        return new RecoveryAttempt(order.getUser().getId(), new ConfirmPaymentRequest(
                order.getPaymentKey(), order.getOrderId(), order.getTotalAmount()), order.getRecoveryAttempts(), null);
    }

    /**
     * 토스가 결제 키를 모른다고 반복해서 답한 주문만 결제 실패로 확정해 재고를 푼다.
     * 토스는 결제창이 열릴 때부터 키를 조회할 수 있으므로, 결제 기한과 유예 시간이 지나도록
     * 계속 404라면 실제 결제가 존재할 가능성은 낮다. 다른 조회 실패는 승인 가능성이 있어 재고를 유지한다.
     */
    private boolean failMissingPayment(Long id, int attempt, TransactionTemplate transaction) {
        try {
            Boolean failed = transaction.execute(status -> {
                Order order = orderRepository.findByIdForUpdate(id).orElse(null);
                if (order == null || order.getStatus() != OrderStatus.CONFIRMING
                        || order.getRecoveryAttempts() != attempt || attempt < MISSING_PAYMENT_MIN_LOOKUPS
                        || order.getExpiresAt() == null
                        || LocalDateTime.now().isBefore(order.getExpiresAt().plus(MISSING_PAYMENT_GRACE))
                        || paymentRepository.findByOrderOrderId(order.getOrderId()).isPresent()) {
                    return false;
                }
                order.setStatus(OrderStatus.PAYMENT_FAILED);
                order.setNextRecoveryAt(null);
                order.setRecoveryReviewRequired(false);
                return true;
            });
            if (Boolean.TRUE.equals(failed)) {
                log.warn("토스에 없는 결제 키로 확인되어 결제 실패 처리. orderPk={}, attempt={}", id, attempt);
                return true;
            }
        } catch (RuntimeException failure) {
            log.error("결제 실패 확정 저장 실패. 다음 주기에 다시 확인합니다. orderPk={}", id, failure);
        }
        return false;
    }

    private void recordRecoveryFailure(Long id, int attempt, TransactionTemplate transaction) {
        try {
            transaction.executeWithoutResult(status -> {
                Order order = orderRepository.findByIdForUpdate(id).orElse(null);
                if (order != null && isUnfinished(order) && order.getRecoveryAttempts() == attempt
                        && attempt >= recoveryMaxAttempts) {
                    order.setRecoveryReviewRequired(true);
                    order.setNextRecoveryAt(null);
                    log.error("결제 자동 복구 한도 초과. 수동 확인 필요. orderId={}", order.getOrderId());
                }
            });
        } catch (RuntimeException failure) {
            log.error("복구 실패 기록 저장 실패. 다음 주기에 DB 상태를 다시 확인합니다. orderId={}", id, failure);
        }
    }

    private void finishUnpaidOrder(Long userId, ConfirmPaymentRequest request, OrderStatus terminal) {
        Order order = orderRepository.findByOrderIdAndUserIdForUpdate(request.orderId(), userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다."));
        if (!request.paymentKey().equals(order.getPaymentKey()) || !request.amount().equals(order.getTotalAmount())
                || paymentRepository.findByOrderOrderId(request.orderId()).isPresent()
                || (order.getStatus() != terminal && !isUnfinished(order))
                || (order.getStatus() == OrderStatus.CANCELING && terminal != OrderStatus.CANCELED)) {
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_STATE_MISMATCH", "결제 상태를 확정할 수 없습니다.");
        }
        order.setStatus(terminal);
        order.setNextRecoveryAt(null);
        order.setRecoveryReviewRequired(false);
    }

    private boolean isUnfinished(Order order) {
        return order.getStatus() == OrderStatus.CONFIRMING || order.getStatus() == OrderStatus.CANCELING;
    }

    private LocalDateTime nextRecoveryAt() {
        return LocalDateTime.now().plusNanos(recoveryRetryDelayMs * 1_000_000);
    }

    private record RecoveryAttempt(Long userId, ConfirmPaymentRequest request, int number, String cancellationKey) {}

    private PaymentResponse matchingPaidResponse(Order order, ConfirmPaymentRequest request, Payment payment) {
        if (payment == null || !request.paymentKey().equals(payment.getPaymentKey())
                || !request.amount().equals(payment.getAmount()) || !request.amount().equals(order.getTotalAmount())
                || !"DONE".equals(payment.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_STATE_MISMATCH", "저장된 결제 정보가 요청과 일치하지 않습니다.");
        }
        return toResponse(payment);
    }
}
