package com.example.catalogservice.config;

import com.example.catalogservice.repository.BrandRepository;
import com.example.catalogservice.repository.CategoryRepository;
import com.example.catalogservice.repository.ProductRepository;
import com.example.catalogservice.repository.SkuRepository;
import com.example.catalogservice.repository.SkuStockSnapshotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "catalog.seeder.enabled=true",
        "management.health.elasticsearch.enabled=false",
        "security.internal-service-keys.order-service=order-service-secret-key-32chars",
        "security.internal-service-keys.notification-service=notification-service-secret-key-32c",
        "security.gateway-assertion.public-key=MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAr+qpeFC7N9yYQYKyKYd3wWHnhth0aithJSysJifsaz1k3n0QKMOejmJxK8BrGwYGSH7tCjlRkOUPG5h4jWgGuv3CgnCJR8hzSx362wYkcykI4e0Umjya3ebd2ArPKN0NgZ0xfIphtsS+OQCKFOF5FcZr/T14qP62Dtr+8K91VltTiNbOTxrGxBLfhxf9CZ9iSYZy3xnQW2lwL+wblp2ps8qOBLvCp8QP81Ffa7M8IM6qTX24g5FnmCub/jPJ/9oqJNFvRw5hRHQvNLvY72P9a+nN4Jg4N36A2djbAR9Ws4kWkB4Inrbwg/kVrxWafrPoXCZG+aPcF3pbJ1Sz333EFwIDAQAB"
})
class CatalogDataSeederIntegrationTests {

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private SkuRepository skuRepository;

    @Autowired
    private SkuStockSnapshotRepository skuStockSnapshotRepository;

    @Test
    @DisplayName("Positive Integration: CatalogDataSeeder initializes sample data on startup when enabled")
    void testCatalogDataSeeder_ExecutedOnStartup() {
        assertThat(categoryRepository.count()).isEqualTo(3L);
        assertThat(brandRepository.count()).isEqualTo(3L);
        assertThat(productRepository.count()).isEqualTo(2L);
        assertThat(skuRepository.count()).isEqualTo(3L);
        assertThat(skuStockSnapshotRepository.count()).isEqualTo(3L);
    }
}
