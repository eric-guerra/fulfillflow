package com.ericguerra.fulfillflow.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Releases waves on a fixed cadence during operating hours (cron configurable per site). */
@Component
@ConditionalOnProperty(name = "fulfillflow.wave.schedule-enabled", havingValue = "true", matchIfMissing = true)
public class WaveScheduler {
    private static final Logger log = LoggerFactory.getLogger(WaveScheduler.class);
    private final JobLauncher launcher;
    private final Job waveJob;

    public WaveScheduler(JobLauncher launcher, @Qualifier("waveJob") Job waveJob) {
        this.launcher = launcher;
        this.waveJob = waveJob;
    }

    @Scheduled(cron = "${fulfillflow.wave.cron:0 */15 6-20 * * *}")
    public void run() throws Exception {
        var exec = launcher.run(waveJob, new JobParametersBuilder().addLong("ts", System.currentTimeMillis()).toJobParameters());
        log.info("waveJob {} -> {}", exec.getId(), exec.getStatus());
    }
}
