package com.example.catalogservice.config;

import com.example.catalogservice.elasticsearch.repository.ProductSkuRepository;
import com.example.catalogservice.elasticsearch.service.ElasticsearchIndexService;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

@Configuration
@ConditionalOnProperty(name = "spring.data.elasticsearch.repositories.enabled", havingValue = "false", matchIfMissing = true)
public class TestElasticsearchConfig {

    @Bean(name = {"elasticsearchTemplate", "elasticsearchOperations"})
    @Primary
    public ElasticsearchOperations elasticsearchOperations() {
        return Mockito.mock(ElasticsearchOperations.class);
    }

    @Bean(name = "productSkuRepository")
    @Primary
    public ProductSkuRepository productSkuRepository() {
        return Mockito.mock(ProductSkuRepository.class);
    }

    @Bean(name = "elasticsearchIndexService")
    @Primary
    public ElasticsearchIndexService elasticsearchIndexService() {
        return Mockito.mock(ElasticsearchIndexService.class);
    }
}
