package com.example.catalogservice.elasticsearch.repository;

import com.example.catalogservice.elasticsearch.document.ProductSkuDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductSkuRepository extends ElasticsearchRepository<ProductSkuDocument, String> {

    List<ProductSkuDocument> findByProductId(String productId);

    void deleteByProductId(String productId);
}
