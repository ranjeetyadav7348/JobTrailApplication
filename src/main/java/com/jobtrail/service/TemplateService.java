package com.jobtrail.service;

import com.jobtrail.domain.EmailTemplate;
import com.jobtrail.domain.Outreach;
import com.jobtrail.domain.TemplateKind;
import com.jobtrail.repo.EmailTemplateRepository;
import com.jobtrail.repo.OutreachRepository;
import com.jobtrail.web.ApiException;
import com.jobtrail.web.dto.TemplateForm;
import com.jobtrail.web.dto.Views;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class TemplateService {

    private final EmailTemplateRepository repo;
    private final OutreachRepository outreachRepo;

    @Transactional(readOnly = true)
    public List<Views.TemplateView> list() {
        return repo.findAllByOrderByKindAscNameAsc().stream().map(Views::of).toList();
    }

    @Transactional
    public Views.TemplateView create(TemplateForm f) {
        EmailTemplate t = new EmailTemplate();
        apply(t, f);
        t = repo.save(t);
        if (Boolean.TRUE.equals(f.getIsDefault())) {
            makeDefault(t);
        }
        return Views.of(t);
    }

    @Transactional
    public Views.TemplateView update(Long id, TemplateForm f) {
        EmailTemplate t = require(id);
        apply(t, f);
        t = repo.save(t);
        if (Boolean.TRUE.equals(f.getIsDefault())) {
            makeDefault(t);
        }
        return Views.of(t);
    }

    @Transactional
    public Views.TemplateView setDefault(Long id) {
        EmailTemplate t = require(id);
        makeDefault(t);
        return Views.of(t);
    }

    @Transactional
    public void delete(Long id) {
        EmailTemplate t = require(id);
        if (repo.findByKindOrderByNameAsc(t.getKind()).size() <= 1) {
            throw ApiException.conflict("This is the only "
                    + t.getKind().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                    + " template left — create another one before deleting it.");
        }
        // Drop references so no thread points at a template that no longer exists.
        List<Outreach> referencing = outreachRepo.findAll().stream()
                .filter(o -> id.equals(o.getInitialTemplateId()) || id.equals(o.getFollowUpTemplateId()))
                .toList();
        for (Outreach o : referencing) {
            if (id.equals(o.getInitialTemplateId())) {
                o.setInitialTemplateId(null);
            }
            if (id.equals(o.getFollowUpTemplateId())) {
                o.setFollowUpTemplateId(null);
            }
        }
        outreachRepo.saveAll(referencing);
        repo.delete(t);
    }

    private void makeDefault(EmailTemplate target) {
        List<EmailTemplate> siblings = repo.findByKindOrderByNameAsc(target.getKind());
        for (EmailTemplate t : siblings) {
            t.setDefault(t.getId().equals(target.getId()));
        }
        repo.saveAll(siblings);
        target.setDefault(true);
    }

    private void apply(EmailTemplate t, TemplateForm f) {
        t.setName(f.getName().trim());
        t.setSubject(f.getSubject() == null ? "" : f.getSubject().trim());
        t.setBodyHtml(f.getBodyHtml());
        if (f.getKind() != null && !f.getKind().isBlank()) {
            try {
                t.setKind(TemplateKind.valueOf(f.getKind().trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Template kind must be INITIAL or FOLLOW_UP");
            }
        }
    }

    private EmailTemplate require(Long id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("Template " + id + " not found"));
    }
}
