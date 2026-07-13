package com.sms.extraction.repository;

import com.sms.extraction.domain.GlobalEntity;

import java.util.List;

public interface EntityRepository {

    List<GlobalEntity> findBySenderId(String senderId);

    void save(String senderId, List<GlobalEntity> entities);
}
