package com.ibdev.bot.zara.storage.repo;

import com.ibdev.bot.zara.storage.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * @author i.bogatskii
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, String> {
}
