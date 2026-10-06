package com.ziprun.config;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Thread pools, kept apart so one kind of slow work can't starve another:
 *
 *  - applicationTaskExecutor (spring.task.execution.*): the @Async re-planning loop
 *  - this one: "Get suggestion" streams, one thread per open stream while the AI answers
 *  - the @Scheduled pool (spring.task.scheduling.pool.size): heartbeat check, deadline check, keep-alives,
 *    so a deadline check waiting on the AI never delays the heartbeat check
 *
 * Deliberately a component that *holds* its pool rather than an Executor bean: Spring Boot stops
 * auto-configuring applicationTaskExecutor when it sees another Executor bean.
 */
@Component
public class StreamWorkers {

    private final ThreadPoolTaskExecutor streams = new ThreadPoolTaskExecutor();

    public StreamWorkers(@Value("${streams.pool.core-size:4}") int core,
                       @Value("${streams.pool.max-size:16}") int max) {
        streams.setThreadNamePrefix("ziprun-stream-");
        streams.setCorePoolSize(core);
        streams.setMaxPoolSize(max);
        // No queue: a stream waiting behind others would look frozen. Past the maximum the
        // request is refused at once and the console says so.
        streams.setQueueCapacity(0);
        streams.initialize();
    }

    /**
     * @throws TaskRejectedException when every stream thread is busy
     */
    public void runStream(Runnable task) {
        streams.execute(task);
    }

    @PreDestroy
    void shutdown() {
        streams.shutdown();
    }
}
