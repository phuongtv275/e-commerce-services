package com.example.catalogservice.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "skus", indexes = {
        @Index(name = "idx_skus_product_id", columnList = "product_id"),
        @Index(name = "idx_skus_status", columnList = "status"),
        @Index(name = "idx_skus_deleted_at", columnList = "deleted_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Sku {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "sku_code", nullable = false, unique = true, length = 100)
    private String skuCode;

    @Column(name = "name", nullable = false, length = 500)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes")
    private JsonNode attributes;

    @Column(name = "price", nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Column(name = "currency", nullable = false, length = 10)
    @Builder.Default
    private String currency = "VND";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private SkuStatus status = SkuStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public String getAttributes() {
        if (attributes == null) {
            return "{}";
        }
        return attributes.isTextual() ? attributes.asText() : attributes.toString();
    }

    public JsonNode getAttributesNode() {
        return attributes;
    }

    public void setAttributes(String attributesJson) {
        this.attributes = parseJson(attributesJson);
    }

    public void setAttributes(JsonNode attributesNode) {
        this.attributes = attributesNode;
    }

    public static class SkuBuilder {
        public SkuBuilder attributes(String attributesJson) {
            this.attributes = parseJson(attributesJson);
            return this;
        }

        public SkuBuilder attributes(JsonNode attributesNode) {
            this.attributes = attributesNode;
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
        if (currency == null) {
            currency = "VND";
        }
        if (status == null) {
            status = SkuStatus.ACTIVE;
        }
        if (attributes == null) {
            attributes = OBJECT_MAPPER.createObjectNode();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
