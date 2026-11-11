package ro.ghil.memoriesbe.events;

import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the purge every night at 03:30 UTC. */
@Component
class EventPurgeJob {
    private static final Logger log = LoggerFactory.getLogger(EventPurgeJob.class);

    private final EventPurger purger;

    EventPurgeJob(EventPurger purger) {
        this.purger = purger;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    void run() {
        int n = purger.purgeDue(OffsetDateTime.now());
        if (n > 0) log.info("Purged the images of {} expired events", n);
    }
}
