package wamddu.backend.order.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import wamddu.backend.order.domain.Order;
import wamddu.backend.order.domain.OrderStatus;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByOrderId(String orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.orderId=:orderId")
    Optional<Order> findByOrderIdForUpdate(@Param("orderId") String orderId);

    Optional<Order> findByOrderIdAndUserId(String orderId, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT O FROM Order O JOIN FETCH O.user WHERE O.orderId = :orderId AND O.user.id = :userId")
    Optional<Order> findByOrderIdAndUserIdForUpdate(
            @Param("orderId") String orderId,
            @Param("userId") Long userId
    );

    @Query("SELECT COALESCE(SUM(O.quantity), 0) FROM Order O " +
            "WHERE O.ticket_id = :ticketId AND O.status IN :statuses " +
            "AND (O.status <> wamddu.backend.order.domain.OrderStatus.CANCELING " +
            "OR NOT EXISTS (SELECT P.id FROM Payment P WHERE P.order = O)) " +
            "AND (O.expiresAt > :now OR O.status IN " +
            "(wamddu.backend.order.domain.OrderStatus.CONFIRMING, wamddu.backend.order.domain.OrderStatus.CANCELING))")
    Long sumActiveQuantity(
            @Param("ticketId") Long ticketId,
            @Param("statuses") Collection<OrderStatus> statuses,
            @Param("now") LocalDateTime now
    );

    // Same hold rules as sumActiveQuantity, limited to one buyer.
    @Query("SELECT COALESCE(SUM(O.quantity), 0) FROM Order O " +
            "WHERE O.ticket_id = :ticketId AND O.user.id = :userId AND O.status IN :statuses " +
            "AND (O.status <> wamddu.backend.order.domain.OrderStatus.CANCELING " +
            "OR NOT EXISTS (SELECT P.id FROM Payment P WHERE P.order = O)) " +
            "AND (O.expiresAt > :now OR O.status IN " +
            "(wamddu.backend.order.domain.OrderStatus.CONFIRMING, wamddu.backend.order.domain.OrderStatus.CANCELING))")
    Long sumActiveQuantityByUser(
            @Param("ticketId") Long ticketId,
            @Param("userId") Long userId,
            @Param("statuses") Collection<OrderStatus> statuses,
            @Param("now") LocalDateTime now
    );

    List<Order> findAllByUserIdAndStatusOrderByPaidAtDesc(Long userId, OrderStatus status);

    @Query("SELECT COALESCE(SUM(O.quantity), 0) FROM Order O WHERE O.ticket_id = :ticketId " +
            "AND (O.status = wamddu.backend.order.domain.OrderStatus.PAID OR " +
            "(O.status = wamddu.backend.order.domain.OrderStatus.CANCELING " +
            "AND EXISTS (SELECT P.id FROM Payment P WHERE P.order = O AND P.status = 'DONE'))) " +
            "AND O.paidAt >= :from AND O.paidAt < :to")
    Long sumPaidQuantity(@Param("ticketId") Long ticketId,
                         @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT O.id FROM Order O WHERE O.status IN " +
            "(wamddu.backend.order.domain.OrderStatus.CONFIRMING, wamddu.backend.order.domain.OrderStatus.CANCELING) " +
            "AND O.recoveryReviewRequired = false AND (O.nextRecoveryAt IS NULL OR O.nextRecoveryAt <= :now) " +
            "ORDER BY O.nextRecoveryAt, O.id")
    List<Long> findRecoveryCandidates(@Param("now") LocalDateTime now, Pageable pageable);

    // Monitoring: payments the recovery scheduler is still working on, and ones it handed to a person.
    long countByStatusAndRecoveryReviewRequiredFalse(OrderStatus status);

    long countByStatusInAndRecoveryReviewRequiredTrue(Collection<OrderStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT O FROM Order O JOIN FETCH O.user WHERE O.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);
}
