package com.sms.extraction.service;

public interface SenderLearningStateService {

    enum LearningState { LEARNING_IN_PROGRESS, LEARNED, LEARNING_FAILED }

    boolean tryClaimLearning(String senderId);
    void acquireLock(String senderId);
    void releaseLock(String senderId);
    void setState(String senderId, LearningState state);
    LearningState getState(String senderId);
    void waitForCompletion(String senderId);
    void publishCompletion(String senderId);
}
