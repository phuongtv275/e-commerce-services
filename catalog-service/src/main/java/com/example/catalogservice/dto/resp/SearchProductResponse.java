package com.example.catalogservice.dto.resp;

import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchProductResponse {

    private List<ProductSearchItem> items;

    private Integer page;

    private Integer size;

    private Long total;

    private String nextCursor;

    private String source;
}
