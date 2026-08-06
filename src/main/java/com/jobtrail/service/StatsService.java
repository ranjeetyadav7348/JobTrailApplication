package com.jobtrail.service;

import com.jobtrail.domain.MessageStatus;
import com.jobtrail.domain.Outreach;
import com.jobtrail.domain.OutreachStatus;
import com.jobtrail.repo.EmailMessageRepository;
import com.jobtrail.repo.OutreachRepository;
import com.jobtrail.web.dto.Views;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StatsService {

    private static final int CHART_DAYS = 14;

    private final OutreachRepository outreachRepo;
    private final EmailMessageRepository messageRepo;
    private final SendQueueService queueService;

    @Transactional(readOnly = true)
    public Views.StatsView dashboard() {
        List<Outreach> all = outreachRepo.findAll();

        Map<OutreachStatus, Long> byStatus = new EnumMap<>(OutreachStatus.class);
        for (OutreachStatus st : OutreachStatus.values()) {
            byStatus.put(st, 0L);
        }
        for (Outreach o : all) {
            byStatus.merge(o.getStatus(), 1L, Long::sum);
        }

        long threadsSent = all.stream().filter(o -> o.getFirstSentAt() != null).count();
        long opened = all.stream().filter(o -> o.getOpenedAt() != null).count();
        long replied = all.stream().filter(o -> o.getRepliedAt() != null).count();

        List<Views.StatusCount> statusCounts = byStatus.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> new Views.StatusCount(e.getKey().name(), e.getValue()))
                .toList();

        Instant now = Instant.now();
        List<Views.OutreachView> upcoming = all.stream()
                .filter(o -> o.getNextFollowUpAt() != null && o.isFollowUpEligible())
                .sorted(Comparator.comparing(Outreach::getNextFollowUpAt))
                .limit(6)
                .map(Views::of)
                .toList();

        return new Views.StatsView(
                all.size(),
                messageRepo.countByStatus(MessageStatus.QUEUED),
                messageRepo.countByStatus(MessageStatus.SENT),
                queueService.sentToday(),
                opened,
                replied,
                byStatus.getOrDefault(OutreachStatus.FAILED, 0L),
                outreachRepo.findFollowUpsDue(now).size(),
                rate(opened, threadsSent),
                rate(replied, threadsSent),
                sendsPerDay(),
                statusCounts,
                queueService.recent(8),
                upcoming);
    }

    /** One bucket per day for the last two weeks, oldest first. */
    private List<Views.DayCount> sendsPerDay() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate from = today.minusDays(CHART_DAYS - 1L);

        Map<LocalDate, Long> counts = new java.util.HashMap<>();
        for (Instant sentAt : messageRepo.findSentTimestampsSince(from.atStartOfDay(zone).toInstant())) {
            if (sentAt != null) {
                counts.merge(sentAt.atZone(zone).toLocalDate(), 1L, Long::sum);
            }
        }

        List<Views.DayCount> series = new ArrayList<>(CHART_DAYS);
        for (int i = 0; i < CHART_DAYS; i++) {
            LocalDate day = from.plusDays(i);
            series.add(new Views.DayCount(day.toString(), counts.getOrDefault(day, 0L)));
        }
        return series;
    }

    private static double rate(long part, long whole) {
        if (whole <= 0) {
            return 0d;
        }
        return Math.round((part * 1000d) / whole) / 10d;
    }
}
