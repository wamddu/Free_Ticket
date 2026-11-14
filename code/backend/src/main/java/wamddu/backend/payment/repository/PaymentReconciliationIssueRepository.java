package wamddu.backend.payment.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import wamddu.backend.payment.domain.PaymentReconciliationIssue;

public interface PaymentReconciliationIssueRepository extends JpaRepository<PaymentReconciliationIssue, Long>,
        JpaSpecificationExecutor<PaymentReconciliationIssue> {
    boolean existsByMerchantIdAndTransactionKey(String merchantId, String transactionKey);

    long countByResolvedAtUtcIsNull();

    interface Reference {
        String getOrderId();
        String getPgPaymentKey();
        String getMerchantId();
    }
    @Query("select i.orderId as orderId, i.pgPaymentKey as pgPaymentKey, i.merchantId as merchantId " +
            "from PaymentReconciliationIssue i where i.id=:id")
    Optional<Reference> findReference(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from PaymentReconciliationIssue i where i.id=:id")
    Optional<PaymentReconciliationIssue> findByIdForUpdate(@Param("id") long id);
}
