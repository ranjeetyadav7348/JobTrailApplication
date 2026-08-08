package com.jobtrail.repo;

import com.jobtrail.domain.ApplicationEvent;
import com.jobtrail.domain.ApplicationEventKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface ApplicationEventRepository extends JpaRepository<ApplicationEvent, Long> {

    boolean existsByMessageId(String messageId);

    /** One round trip to find which of a scanned batch we have already stored. */
    @Query("select e.messageId from ApplicationEvent e where e.messageId in :ids")
    Set<String> findKnownMessageIds(@Param("ids") Collection<String> ids);

    List<ApplicationEvent> findByApplicationIdOrderByReceivedAtDesc(Long applicationId);

    /**
     * Applications that ever produced one of these events. The funnel is built
     * from this rather than from current status, because status is a single
     * value — a rejection after an on-site still means the interview happened.
     */
    @Query("""
            select distinct e.application.id from ApplicationEvent e
            where e.kind in :kinds and e.application.archived = false
            """)
    Set<Long> findApplicationIdsByKinds(@Param("kinds") Collection<ApplicationEventKind> kinds);

    List<ApplicationEvent> findTop20ByOrderByReceivedAtDesc();

    long countByKind(ApplicationEventKind kind);

    @Query("select e from ApplicationEvent e where e.receivedAt >= :since order by e.receivedAt asc")
    List<ApplicationEvent> findSince(@Param("since") Instant since);

    void deleteByApplicationId(Long applicationId);
}
