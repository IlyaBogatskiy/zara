package com.ibdev.bot.zara.storage.repo;

import com.ibdev.bot.zara.storage.model.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * @author i.bogatskii
 */
@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Subscription findByChatIdAndProductKeyAndSizeLabel(Long chatId, String productKey, String sizeLabel);

    List<Subscription> findByChatIdAndProductKeyAndClosedAtIsNull(Long chatId, String productKey);

    List<Subscription> findByProductKeyAndClosedAtIsNull(String productKey);

    List<Subscription> findByClosedAtIsNull();
}
