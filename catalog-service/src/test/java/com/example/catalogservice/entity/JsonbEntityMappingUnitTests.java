package com.example.catalogservice.entity;

import com.example.catalogservice.repository.CatalogAuditLogRepository;
import com.example.catalogservice.service.AuditLogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class JsonbEntityMappingUnitTests {

    @Mock
    private CatalogAuditLogRepository auditLogRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private AuditLogService auditLogService;

    @Test
    @DisplayName("Positive: AuditLogService converts objects to structured JsonNode via valueToTree and preserves nulls")
    void testAuditLogService_PreservesJsonNodeStructureAndNulls() {
        Map<String, Object> beforeState = Map.of("name", "Old Product", "price", 100);
        Map<String, Object> afterState = Map.of("name", "New Product", "price", 150);

        auditLogService.record("admin-1", "ADMIN", "UPDATE_PRODUCT", "PRODUCT", "prod-123", beforeState, afterState);

        ArgumentCaptor<CatalogAuditLog> captor = ArgumentCaptor.forClass(CatalogAuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        CatalogAuditLog saved = captor.getValue();
        assertThat(saved.getActorId()).isEqualTo("admin-1");
        assertThat(saved.getActorType()).isEqualTo("ADMIN");
        assertThat(saved.getAction()).isEqualTo("UPDATE_PRODUCT");

        // Verify beforeState is a structured JsonNode, not a serialized string scalar
        JsonNode beforeNode = saved.getBeforeState();
        assertThat(beforeNode).isNotNull();
        assertThat(beforeNode.isObject()).isTrue();
        assertThat(beforeNode.get("name").asText()).isEqualTo("Old Product");
        assertThat(beforeNode.get("price").asInt()).isEqualTo(100);

        // Verify afterState is a structured JsonNode
        JsonNode afterNode = saved.getAfterState();
        assertThat(afterNode).isNotNull();
        assertThat(afterNode.isObject()).isTrue();
        assertThat(afterNode.get("name").asText()).isEqualTo("New Product");
        assertThat(afterNode.get("price").asInt()).isEqualTo(150);

        // Verify null handling
        auditLogService.record("admin-1", "ADMIN", "CREATE_PRODUCT", "PRODUCT", "prod-456", null, afterState);
        verify(auditLogRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        CatalogAuditLog createSaved = captor.getValue();
        assertThat(createSaved.getBeforeState()).isNull();
        assertThat(createSaved.getAfterState()).isNotNull();
    }

    @Test
    @DisplayName("Positive: Product entity parses JSON string to JsonNode metadata preserving API contract")
    void testProductMetadata_JsonbMapping() throws Exception {
        String json = "{\"specs\":{\"cpu\":\"M3\",\"ram\":\"16GB\"}}";

        // Builder with String
        Product p1 = Product.builder()
                .name("MacBook Pro")
                .metadata(json)
                .build();
        assertThat(p1.getMetadata()).contains("cpu");
        assertThat(p1.getMetadataNode()).isNotNull();
        assertThat(p1.getMetadataNode().isObject()).isTrue();
        assertThat(p1.getMetadataNode().at("/specs/cpu").asText()).isEqualTo("M3");

        // Setter with String
        Product p2 = new Product();
        p2.setMetadata("{\"color\":\"Space Gray\"}");
        assertThat(p2.getMetadataNode()).isNotNull();
        assertThat(p2.getMetadataNode().get("color").asText()).isEqualTo("Space Gray");
        assertThat(p2.getMetadata()).contains("Space Gray");

        // Builder with JsonNode directly
        JsonNode node = objectMapper.readTree("{\"weight\":\"1.5kg\"}");
        Product p3 = Product.builder()
                .metadata(node)
                .build();
        assertThat(p3.getMetadataNode()).isEqualTo(node);
        assertThat(p3.getMetadata()).contains("1.5kg");
    }

    @Test
    @DisplayName("Positive: Sku entity parses JSON string to JsonNode attributes preserving API contract")
    void testSkuAttributes_JsonbMapping() throws Exception {
        String json = "{\"color\":\"Silver\",\"storage\":\"512GB\"}";

        // Builder with String
        Sku sku1 = Sku.builder()
                .name("Silver SKU")
                .skuCode("SKU-SILVER-512")
                .price(new BigDecimal("25000000"))
                .attributes(json)
                .build();
        assertThat(sku1.getAttributes()).contains("Silver");
        assertThat(sku1.getAttributesNode()).isNotNull();
        assertThat(sku1.getAttributesNode().isObject()).isTrue();
        assertThat(sku1.getAttributesNode().get("color").asText()).isEqualTo("Silver");
        assertThat(sku1.getAttributesNode().get("storage").asText()).isEqualTo("512GB");

        // Setter with String
        Sku sku2 = new Sku();
        sku2.setAttributes("{\"ram\":\"32GB\"}");
        assertThat(sku2.getAttributesNode()).isNotNull();
        assertThat(sku2.getAttributesNode().get("ram").asText()).isEqualTo("32GB");
        assertThat(sku2.getAttributes()).contains("32GB");

        // Builder with JsonNode directly
        JsonNode node = objectMapper.readTree("{\"edition\":\"Special\"}");
        Sku sku3 = Sku.builder()
                .attributes(node)
                .build();
        assertThat(sku3.getAttributesNode()).isEqualTo(node);
        assertThat(sku3.getAttributes()).contains("Special");
    }

    @Test
    @DisplayName("Positive: Malformed, blank, or whitespace-only JSON preserves raw string content across round-trip without discarding caller data")
    void testMalformedAndBlankJson_PreservesRawTextRoundTrip() {
        String malformedMetadata = "this is not json { [ 123";
        Product product = new Product();
        product.setMetadata(malformedMetadata);

        assertThat(product.getMetadataNode()).isNotNull();
        assertThat(product.getMetadataNode().isTextual()).isTrue();
        assertThat(product.getMetadata()).isEqualTo(malformedMetadata);

        // Blank and whitespace-only String assertions
        Product blankProduct = new Product();
        blankProduct.setMetadata("   ");
        assertThat(blankProduct.getMetadataNode()).isNotNull();
        assertThat(blankProduct.getMetadataNode().isTextual()).isTrue();
        assertThat(blankProduct.getMetadata()).isEqualTo("   ");

        String malformedAttributes = "corrupted:attributes:{xyz";
        Sku sku = new Sku();
        sku.setAttributes(malformedAttributes);

        assertThat(sku.getAttributesNode()).isNotNull();
        assertThat(sku.getAttributesNode().isTextual()).isTrue();
        assertThat(sku.getAttributes()).isEqualTo(malformedAttributes);

        Sku blankSku = new Sku();
        blankSku.setAttributes("");
        assertThat(blankSku.getAttributesNode()).isNotNull();
        assertThat(blankSku.getAttributesNode().isTextual()).isTrue();
        assertThat(blankSku.getAttributes()).isEqualTo("");
    }
}
