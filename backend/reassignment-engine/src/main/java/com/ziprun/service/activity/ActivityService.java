package com.ziprun.service.activity;

import com.ziprun.domain.Activity;
import com.ziprun.repository.ActivityRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Append-only history of decisions and changes. Records join the caller's
 * transaction, so an entry exists only if the change it describes committed.
 */
@Service
public class ActivityService {

    private final ActivityRepository repository;

    public ActivityService(ActivityRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(Activity.Type type, Activity.Actor actor, String message) {
        record(type, actor, message, null, null, null);
    }

    @Transactional
    public void record(Activity.Type type, Activity.Actor actor, String message,
                       String orderId, String agentId, String suggestionId) {
        repository.save(new Activity(type, actor, message, orderId, agentId, suggestionId));
    }

    @Transactional(readOnly = true)
    public List<Activity> recent(int limit) {
        return repository.findAllByOrderByIdDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 500))));
    }
}
