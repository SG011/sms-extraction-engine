package com.sms.extraction.service.impl;

import com.sms.extraction.service.SenderLearningStateService;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Service
public class SenderLearningStateServiceImpl implements SenderLearningStateService {

    private static final Logger log = LoggerFactory.getLogger(SenderLearningStateServiceImpl.class);

    private static final String STATE_KEY_PREFIX = "sms:learning:state:";
    private static final String LOCK_KEY_PREFIX  = "sms:learning:lock:";
    private static final String TOPIC_KEY_PREFIX = "sms:learning:done:";

    private final RedissonClient redissonClient;
    private final int learningInProgressTtlSeconds;

    public SenderLearningStateServiceImpl(RedissonClient redissonClient,
                                           @Value("${redis.learning.ttl-seconds:120}") int learningInProgressTtlSeconds) {
        this.redissonClient = redissonClient;
        this.learningInProgressTtlSeconds = learningInProgressTtlSeconds;
    }

    @Override
    public void acquireLock(String senderId) {
        redissonClient.getLock(LOCK_KEY_PREFIX + senderId).lock();
    }

    @Override
    public void releaseLock(String senderId) {
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + senderId);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    @Override
    public boolean tryClaimLearning(String senderId) {
        RBucket<String> bucket = redissonClient.getBucket(STATE_KEY_PREFIX + senderId);
        // Single atomic operation: set value WITH TTL only if key is absent
        // Prevents stale keys if the process crashes between set and expire
        return bucket.setIfAbsent(LearningState.LEARNING_IN_PROGRESS.name(),
                java.time.Duration.ofSeconds(learningInProgressTtlSeconds));
    }

    @Override
    public void setState(String senderId, LearningState state) {
        RBucket<String> bucket = redissonClient.getBucket(STATE_KEY_PREFIX + senderId);
        if (state == LearningState.LEARNING_IN_PROGRESS) {
            bucket.set(state.name(), learningInProgressTtlSeconds, TimeUnit.SECONDS);
        } else {
            // LEARNED / LEARNING_FAILED: delete the key so the next thread can claim via setIfAbsent.
            // Keeping the key as LEARNED would cause waiting threads to loop forever (setIfAbsent
            // returns false for any existing key, and no one publishes after learning is done).
            bucket.delete();
        }
        log.debug("Sender {} state → {}", senderId, state);
    }

    @Override
    public LearningState getState(String senderId) {
        String value = redissonClient.<String>getBucket(STATE_KEY_PREFIX + senderId).get();
        if (value == null) return null;
        return LearningState.valueOf(value);
    }

    @Override
    public void waitForCompletion(String senderId) {
        CountDownLatch latch = new CountDownLatch(1);
        RTopic topic = redissonClient.getTopic(TOPIC_KEY_PREFIX + senderId);
        int listenerId = topic.addListener(String.class, (channel, msg) -> latch.countDown());
        try {
            boolean signalled = latch.await(learningInProgressTtlSeconds, TimeUnit.SECONDS);
            if (!signalled) {
                log.warn("Timed out waiting for sender {} to finish learning", senderId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            topic.removeListener(listenerId);
        }
    }

    @Override
    public void publishCompletion(String senderId) {
        redissonClient.getTopic(TOPIC_KEY_PREFIX + senderId).publish("done");
    }
}
