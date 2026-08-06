package com.jobtrail.repo;

import com.jobtrail.domain.EmailMessage;
import com.jobtrail.domain.MessageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EmailMessageRepository extends JpaRepository<EmailMessage, Long> {

    /** The next email the dispatcher is allowed to touch. */
    Optional<EmailMessage> findFirstByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAscIdAsc(
            MessageStatus status, Instant now);

    Optional<EmailMessage> findFirstByStatusOrderByScheduledAtAscIdAsc(MessageStatus status);

    List<EmailMessage> findByStatusOrderByScheduledAtAscIdAsc(MessageStatus status);

    List<EmailMessage> findByOutreachIdOrderBySequenceNoAscIdAsc(Long outreachId);

    List<EmailMessage> findByOutreachIdAndStatus(Long outreachId, MessageStatus status);

    Optional<EmailMessage> findByTrackingToken(String trackingToken);

    long countByStatus(MessageStatus status);

    long countByStatusAndSentAtGreaterThanEqual(MessageStatus status, Instant from);

    @Query("select m.sentAt from EmailMessage m where m.status = com.jobtrail.domain.MessageStatus.SENT and m.sentAt >= :from")
    List<Instant> findSentTimestampsSince(@Param("from") Instant from);

    @Query("""
            select m from EmailMessage m
            join fetch m.outreach
            order by coalesce(m.sentAt, m.queuedAt) desc, m.id desc
            """)
    List<EmailMessage> findRecentWithOutreach(org.springframework.data.domain.Pageable pageable);
}
