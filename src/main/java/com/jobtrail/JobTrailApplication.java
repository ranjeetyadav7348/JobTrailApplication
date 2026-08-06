package com.jobtrail;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JobTrailApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobTrailApplication.class, args);
    }
}
