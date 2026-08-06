package com.jobtrail.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "jobtrail")
@Getter
@Setter
public class JobTrailProperties {

    /**
     * Hard lower bound, in seconds, for the gap between two real sends.
     * The UI clamps to this value; it exists so a mis-typed setting can never
     * turn the app into a burst sender.
     */
    private int minIntervalFloorSeconds = 5;

    /** How often the dispatcher looks at the queue. */
    private long dispatcherPollMs = 1000;

    /** Base URL used to build open-tracking pixel links. */
    private String publicBaseUrl = "http://localhost:8080";
}
