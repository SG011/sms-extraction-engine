package com.sms.extraction.service;

import com.sms.extraction.service.impl.SenderLearningStateServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doReturn;

@ExtendWith(MockitoExtension.class)
class SenderLearningStateServiceTest {

    @Mock
    private RedissonClient redissonClient;

    @SuppressWarnings("rawtypes")
    @Mock
    private RBucket bucket;

    @Mock
    private RLock lock;

    @Mock
    private RTopic topic;

    private SenderLearningStateService service;

    private static final String SENDER = "HDFC-BANK";

    @BeforeEach
    void setUp() {
        service = new SenderLearningStateServiceImpl(redissonClient, 120);
    }

    @Test
    @DisplayName("setState LEARNING_IN_PROGRESS sets value with TTL")
    void setStateLearningInProgressUsesTtl() {
        doReturn(bucket).when(redissonClient).getBucket("sms:learning:state:" + SENDER);

        service.setState(SENDER, SenderLearningStateService.LearningState.LEARNING_IN_PROGRESS);

        verify(bucket).set("LEARNING_IN_PROGRESS", 120, TimeUnit.SECONDS);
        verify(bucket, never()).set(eq("LEARNING_IN_PROGRESS"));
    }

    @Test
    @DisplayName("setState LEARNED deletes the key so next thread can claim via setIfAbsent")
    void setStateLearnedDeletesKey() {
        doReturn(bucket).when(redissonClient).getBucket("sms:learning:state:" + SENDER);

        service.setState(SENDER, SenderLearningStateService.LearningState.LEARNED);

        verify(bucket).delete();
        verify(bucket, never()).set(any());
    }

    @Test
    @DisplayName("setState LEARNING_FAILED deletes the key so next thread can claim via setIfAbsent")
    void setStateLearningFailedDeletesKey() {
        doReturn(bucket).when(redissonClient).getBucket("sms:learning:state:" + SENDER);

        service.setState(SENDER, SenderLearningStateService.LearningState.LEARNING_FAILED);

        verify(bucket).delete();
        verify(bucket, never()).set(any());
    }

    @Test
    @DisplayName("getState returns null when key absent")
    void getStateReturnsNullWhenAbsent() {
        doReturn(bucket).when(redissonClient).getBucket("sms:learning:state:" + SENDER);
        when(bucket.get()).thenReturn(null);

        assertThat(service.getState(SENDER)).isNull();
    }

    @Test
    @DisplayName("getState returns LEARNING_IN_PROGRESS when set")
    void getStateReturnsLearningInProgress() {
        doReturn(bucket).when(redissonClient).getBucket("sms:learning:state:" + SENDER);
        when(bucket.get()).thenReturn("LEARNING_IN_PROGRESS");

        assertThat(service.getState(SENDER))
                .isEqualTo(SenderLearningStateService.LearningState.LEARNING_IN_PROGRESS);
    }

    @Test
    @DisplayName("getState returns LEARNED when set")
    void getStateReturnsLearned() {
        doReturn(bucket).when(redissonClient).getBucket("sms:learning:state:" + SENDER);
        when(bucket.get()).thenReturn("LEARNED");

        assertThat(service.getState(SENDER))
                .isEqualTo(SenderLearningStateService.LearningState.LEARNED);
    }

    @Test
    @DisplayName("publishCompletion publishes 'done' on senderId topic")
    void publishCompletionSendsDoneMessage() {
        when(redissonClient.getTopic("sms:learning:done:" + SENDER)).thenReturn(topic);

        service.publishCompletion(SENDER);

        verify(topic).publish("done");
    }

    @Test
    @DisplayName("acquireLock calls lock() on RLock for senderId")
    void acquireLockCallsLock() {
        when(redissonClient.getLock("sms:learning:lock:" + SENDER)).thenReturn(lock);

        service.acquireLock(SENDER);

        verify(lock).lock();
    }

    @Test
    @DisplayName("releaseLock calls unlock() when lock is held by current thread")
    void releaseLockUnlocksWhenHeld() {
        when(redissonClient.getLock("sms:learning:lock:" + SENDER)).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        service.releaseLock(SENDER);

        verify(lock).unlock();
    }

    @Test
    @DisplayName("releaseLock does NOT call unlock() when lock is not held by current thread")
    void releaseLockSkipsUnlockWhenNotHeld() {
        when(redissonClient.getLock("sms:learning:lock:" + SENDER)).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        service.releaseLock(SENDER);

        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("tryClaimLearning returns true when key is absent — atomically sets value with TTL")
    void tryClaimLearningReturnsTrueWhenKeyAbsent() {
        when(redissonClient.getBucket("sms:learning:state:" + SENDER)).thenReturn(bucket);
        when(bucket.setIfAbsent("LEARNING_IN_PROGRESS", java.time.Duration.ofSeconds(120))).thenReturn(true);

        assertThat(service.tryClaimLearning(SENDER)).isTrue();
    }

    @Test
    @DisplayName("tryClaimLearning returns false when key already exists")
    void tryClaimLearningReturnsFalseWhenKeyExists() {
        when(redissonClient.getBucket("sms:learning:state:" + SENDER)).thenReturn(bucket);
        when(bucket.setIfAbsent("LEARNING_IN_PROGRESS", java.time.Duration.ofSeconds(120))).thenReturn(false);

        assertThat(service.tryClaimLearning(SENDER)).isFalse();
    }
}
