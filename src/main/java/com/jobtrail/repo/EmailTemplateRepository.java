package com.jobtrail.repo;

import com.jobtrail.domain.EmailTemplate;
import com.jobtrail.domain.TemplateKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmailTemplateRepository extends JpaRepository<EmailTemplate, Long> {

    List<EmailTemplate> findAllByOrderByKindAscNameAsc();

    List<EmailTemplate> findByKindOrderByNameAsc(TemplateKind kind);

    Optional<EmailTemplate> findFirstByKindAndIsDefaultTrue(TemplateKind kind);

    Optional<EmailTemplate> findFirstByKindOrderByIdAsc(TemplateKind kind);
}
