package com.example.catalogservice.elasticsearch.document;

import lombok.*;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageDoc {

    @Field(type = FieldType.Keyword)
    private String url;

    @Field(type = FieldType.Text)
    private String altText;

    @Field(type = FieldType.Boolean)
    private boolean primary;

    @Field(type = FieldType.Integer)
    private int displayOrder;
}
