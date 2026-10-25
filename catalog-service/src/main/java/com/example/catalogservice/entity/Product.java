package com.example.catalogservice.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "products", indexes = {
        @Index(name = "idx_products_category_id", columnList = "category_id"),
        @Index(name = "idx_products_brand_id", columnList = "brand_id"),
        @Index(name = "idx_products_status", columnList = "status"),
        @Index(name = "idx_products_deleted_at", columnList = "deleted_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 500)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private JsonNode metadata;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(name = "brand_id", nullable = false)
    private UUID brandId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ProductStatus status = ProductStatus.DRAFT;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public String getMetadata() {
        if (metadata == null) {
            return "{}";
        }
        return metadata.isTextual() ? metadata.asText() : metadata.toString();
    }

    public JsonNode getMetadataNode() {
        return metadata;
    }

    public void setMetadata(String metadataJson) {
        this.metadata = parseJson(metadataJson);
    }

    public void setMetadata(JsonNode metadataNode) {
        this.metadata = metadataNode;
    }

    public static class ProductBuilder {
        public ProductBuilder metadata(String metadataJson) {
            this.metadata = parseJson(metadataJson);
            return this;
        }

        public ProductBuilder metadata(JsonNode metadataNode) {
            this.metadata = metadataNode;
            return this;
        }
    }

    private static JsonNode parseJson(String json) {
        if (json == null) {
            return null;
        }
        if (json.isBlank()) {
            return new TextNode(json);
        }
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (Exception e) {
            return new TextNode(json);
        }
    }

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
        if (status == null) {
            status = ProductStatus.DRAFT;
        }
        if (metadata == null) {
            metadata = OBJECT_MAPPER.createObjectNode();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
