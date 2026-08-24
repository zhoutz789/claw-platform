package com.claw.server.domain.manufacturer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ProductSkuRepository extends JpaRepository<ProductSku, Long>,
        JpaSpecificationExecutor<ProductSku> {
    java.util.Optional<ProductSku> findBySkuCode(String skuCode);
}
