package com.jobtrail.repo;

import com.jobtrail.domain.Outreach;
import com.jobtrail.domain.OutreachStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutreachRepository extends JpaRepository<Outreach, Long> {

    List<Outreach> findAllByOrderByUpdatedAtDesc();

    List<Outreach> findByRecipientEmailIgnoreCase(String recipientEmail);

    long countByStatus(OutreachStatus status);

    /** Threads whose next follow-up has come due. */
    @Query("""
            select o from Outreach o
            where o.autoFollowUp = true
              and o.status in (com.jobtrail.domain.OutreachStatus.SENT,
                               com.jobtrail.domain.OutreachStatus.OPENED)
              and o.followUpsSent < o.maxFollowUps
              and o.nextFollowUpAt is not null
              and o.nextFollowUpAt <= :now
            order by o.nextFollowUpAt asc
            """)
    List<Outreach> findFollowUpsDue(@Param("now") Instant now);
}
