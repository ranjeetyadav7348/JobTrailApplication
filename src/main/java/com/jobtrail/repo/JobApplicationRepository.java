package com.jobtrail.repo;

import com.jobtrail.domain.ApplicationStatus;
import com.jobtrail.domain.JobApplication;
import com.jobtrail.domain.JobPlatform;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JobApplicationRepository extends JpaRepository<JobApplication, Long> {

    Optional<JobApplication> findByDedupeKey(String dedupeKey);

    /** Same platform and company, any role — used to fold role-less mail into an existing row. */
    List<JobApplication> findByDedupeKeyStartingWithOrderByAppliedAtDesc(String prefix);

    /** Same company and role on any platform — used when the platform could not be identified. */
    List<JobApplication> findByDedupeKeyEndingWith(String suffix);

    List<JobApplication> findByArchivedFalseOrderByLastEventAtDescAppliedAtDesc();

    long countByArchivedFalse();

    long countByStatusAndArchivedFalse(ApplicationStatus status);

    long countByPlatformAndArchivedFalse(JobPlatform platform);

    /** Live applications with an assessment deadline still ahead of them. */
    @Query("""
            select a from JobApplication a
            where a.archived = false
              and a.assessmentDueAt is not null
              and a.assessmentDueAt >= :now
              and a.status not in (com.jobtrail.domain.ApplicationStatus.REJECTED,
                                   com.jobtrail.domain.ApplicationStatus.WITHDRAWN,
                                   com.jobtrail.domain.ApplicationStatus.GHOSTED)
            order by a.assessmentDueAt asc
            """)
    List<JobApplication> findUpcomingDeadlines(@Param("now") Instant now);

    /** Live applications that have heard nothing since {@code cutoff}. */
    @Query("""
            select a from JobApplication a
            where a.archived = false
              and a.status not in (com.jobtrail.domain.ApplicationStatus.OFFER,
                                   com.jobtrail.domain.ApplicationStatus.REJECTED,
                                   com.jobtrail.domain.ApplicationStatus.WITHDRAWN,
                                   com.jobtrail.domain.ApplicationStatus.GHOSTED)
              and coalesce(a.lastEventAt, a.appliedAt) <= :cutoff
            order by coalesce(a.lastEventAt, a.appliedAt) asc
            """)
    List<JobApplication> findStale(@Param("cutoff") Instant cutoff);

    @Query("""
            select a from JobApplication a
            where a.archived = false
              and (lower(a.company) like lower(concat('%', :q, '%'))
                   or lower(coalesce(a.roleTitle, '')) like lower(concat('%', :q, '%'))
                   or lower(coalesce(a.location, '')) like lower(concat('%', :q, '%')))
            order by coalesce(a.lastEventAt, a.appliedAt) desc
            """)
    List<JobApplication> search(@Param("q") String q);
}
