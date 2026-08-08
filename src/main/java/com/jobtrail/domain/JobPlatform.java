package com.jobtrail.domain;

import java.util.List;
import java.util.Locale;

/**
 * Where an application was submitted. Detected from the sending domain and the
 * links inside the mail, so the list is ordered from most specific to least —
 * {@link #DIRECT} and {@link #UNKNOWN} are the fallbacks and never match a domain.
 *
 * <p>Applicant tracking systems (Workday, Greenhouse, …) send from their own
 * infrastructure even when the careers page is on the employer's site, which is
 * what makes this detectable at all.
 */
public enum JobPlatform {

    WORKDAY("Workday", "#0875e1", "myworkdayjobs.com", "myworkday.com", "workday.com", "wd1.myworkdaysite.com", "myworkdaysite.com"),
    GREENHOUSE("Greenhouse", "#24a47f", "greenhouse.io", "grnh.se"),
    LEVER("Lever", "#5c6ac4", "lever.co", "hire.lever.co"),
    ASHBY("Ashby", "#6366f1", "ashbyhq.com"),
    SMARTRECRUITERS("SmartRecruiters", "#0aa5b5", "smartrecruiters.com"),
    ICIMS("iCIMS", "#e0592a", "icims.com"),
    TALEO("Taleo", "#c74634", "taleo.net", "taleo.com"),
    SUCCESSFACTORS("SuccessFactors", "#0a66c2", "successfactors.com", "sapsf.com", "sapsf.eu"),
    WORKABLE("Workable", "#1f6feb", "workable.com"),
    JOBVITE("Jobvite", "#f26722", "jobvite.com"),
    BAMBOOHR("BambooHR", "#79b93c", "bamboohr.com"),
    RECRUITEE("Recruitee", "#ff6b6b", "recruitee.com"),
    ZOHO_RECRUIT("Zoho Recruit", "#e42527", "zohorecruit.com", "zoho.com"),
    LINKEDIN("LinkedIn", "#0a66c2", "linkedin.com"),
    INDEED("Indeed", "#2164f3", "indeed.com", "indeedemail.com"),
    NAUKRI("Naukri", "#4a90d9", "naukri.com", "infoedge.com"),
    INSTAHYRE("Instahyre", "#00b8a9", "instahyre.com"),
    CUTSHORT("Cutshort", "#7c3aed", "cutshort.io"),
    HIRIST("Hirist", "#ff5722", "hirist.com", "hirist.tech"),
    WELLFOUND("Wellfound", "#0d0d0d", "wellfound.com", "angel.co"),
    GLASSDOOR("Glassdoor", "#0caa41", "glassdoor.com", "glassdoor.co.in"),
    MONSTER("Monster", "#6d4aff", "monster.com", "monsterindia.com"),
    SHINE("Shine", "#f7941e", "shine.com"),
    FOUNDIT("Foundit", "#8b5cf6", "foundit.in"),
    DICE("Dice", "#f04e37", "dice.com"),
    ZIPRECRUITER("ZipRecruiter", "#1e9e6a", "ziprecruiter.com"),
    /** Applied straight to a company address, no platform in the loop. */
    DIRECT("Direct", "#8b8a85"),
    /** Recognised as job mail, but the platform could not be pinned down. */
    UNKNOWN("Unknown", "#6b6a66");

    private final String label;
    private final String colour;
    private final List<String> domains;

    JobPlatform(String label, String colour, String... domains) {
        this.label = label;
        this.colour = colour;
        this.domains = List.of(domains);
    }

    public String label() {
        return label;
    }

    /** Chart colour, so the UI does not have to keep its own copy of this list. */
    public String colour() {
        return colour;
    }

    public List<String> domains() {
        return domains;
    }

    /**
     * Longest-suffix match against a host, so {@code jobs.eu.greenhouse.io}
     * resolves and a lookalike such as {@code greenhouse.io.phish.example} does not.
     */
    public static JobPlatform fromHost(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        JobPlatform best = null;
        int bestLength = -1;
        for (JobPlatform p : values()) {
            for (String d : p.domains) {
                if ((h.equals(d) || h.endsWith("." + d)) && d.length() > bestLength) {
                    best = p;
                    bestLength = d.length();
                }
            }
        }
        return best;
    }
}
