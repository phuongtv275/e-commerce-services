package com.example.catalogservice.elasticsearch.document;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "product_catalog")
public class ProductSkuDocument {

    @Id
    private String skuId;

    @Field(type = FieldType.Keyword)
    private String productId;

    @Field(type = FieldType.Keyword)
    private String skuCode;

    @Field(type = FieldType.Keyword)
    private String categoryId;

    @MultiField(mainField = @Field(type = FieldType.Text), otherFields = {
            @InnerField(suffix = "keyword", type = FieldType.Keyword)
    })
    private String categoryName;

    @Field(type = FieldType.Keyword)
    private String brandId;

    @Field(type = FieldType.Keyword)
    private String brandName;

    @MultiField(mainField = @Field(type = FieldType.Text), otherFields = {
            @InnerField(suffix = "keyword", type = FieldType.Keyword)
    })
    private String name;

    @MultiField(mainField = @Field(type = FieldType.Text), otherFields = {
            @InnerField(suffix = "keyword", type = FieldType.Keyword)
    })
    private String productName;

    @Field(type = FieldType.Text)
    private String description;

    @Field(type = FieldType.Scaled_Float, scalingFactor = 10000)
    private Double price;

    @Field(type = FieldType.Keyword)
    private String currency;

    @Field(type = FieldType.Keyword)
    private String status;

    @Field(type = FieldType.Flattened)
    private Map<String, Object> attributes;

    @Field(type = FieldType.Object)
    private List<ImageDoc> images;

    @Field(type = FieldType.Boolean)
    private Boolean inStock;

    @Field(type = FieldType.Long)
    private Long inventoryVersion;

    @Field(type = FieldType.Long)
    private Long aggregateRevision;

    @Field(type = FieldType.Date, format = DateFormat.date_time)
    private Instant updatedAt;
}
