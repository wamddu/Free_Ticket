package wamddu.backend.ticket.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import wamddu.backend.ticket.domain.Ticket;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, Long> {

    Optional<Ticket> findByIdAndEventId(Long id, Long eventId);

    interface QueueTarget {
        Long getEventId();
        boolean isQueueEnabled();
    }

    // Read in its own short transaction before the order transaction (see QueueGate).
    @Query("SELECT E.id AS eventId, E.queueEnabled AS queueEnabled FROM Ticket T JOIN T.event E WHERE T.id = :ticketId")
    Optional<QueueTarget> findQueueTarget(@Param("ticketId") Long ticketId);

    @Query("SELECT T FROM Ticket T JOIN FETCH T.event E WHERE EXISTS " +
            "(SELECT D.id FROM EventDirector D WHERE D.event = E AND D.user.id = :userId) " +
            "ORDER BY T.start_time DESC, T.id ASC")
    List<Ticket> findAllManagedBy(@Param("userId") Long userId);

    @Query("SELECT T FROM Ticket T JOIN FETCH T.event E WHERE T.id = :ticketId AND EXISTS " +
            "(SELECT D.id FROM EventDirector D WHERE D.event = E AND D.user.id = :userId)")
    Optional<Ticket> findManagedTicket(@Param("ticketId") Long ticketId, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT T FROM Ticket T JOIN FETCH T.event E WHERE T.id = :ticketId AND EXISTS " +
            "(SELECT D.id FROM EventDirector D WHERE D.event = E AND D.user.id = :userId)")
    Optional<Ticket> findManagedTicketForUpdate(@Param("ticketId") Long ticketId, @Param("userId") Long userId);

    @Query("SELECT T.id FROM Ticket T WHERE T.automaticPricingEnabled = true " +
            "AND T.bookingEndtime > :now AND T.sold_ticket < T.total_ticket " +
            "AND T.salesStartAt <= :cutoff " +
            "AND (T.lastPriceEvaluatedAt IS NULL OR T.lastPriceEvaluatedAt <= :cutoff)")
    List<Long> findPriceAdjustmentCandidates(@Param("now") java.time.LocalDateTime now,
                                             @Param("cutoff") java.time.LocalDateTime cutoff);

    @Query("SELECT T FROM Ticket T WHERE T.event.id = :id " +
            "ORDER BY T.start_time ASC, T.price DESC")
    List<Ticket> getAllEventTickets(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT T FROM Ticket T JOIN FETCH T.event WHERE T.id = :id")
    Optional<Ticket> findByIdForUpdate(Long id);

    @Query("SELECT T FROM Ticket T LEFT JOIN FETCH T.event WHERE T.id IN :ids")
    List<Ticket> findAllWithEventByIdIn(@Param("ids") List<Long> ids);
}
