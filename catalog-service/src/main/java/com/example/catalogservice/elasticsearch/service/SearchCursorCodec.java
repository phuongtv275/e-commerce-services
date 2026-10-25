package com.example.catalogservice.elasticsearch.service;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.BusinessException;
import com.example.catalogservice.dto.req.SearchProductRequest;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class SearchCursorCodec {

    private final ObjectMapper objectMapper;
    private static final long CURSOR_TTL_MS = 60_000L; // 1 minute keep-alive

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CursorPayload {
        private String pitId;
        private List<Object> sortValues;
        private String fingerprint;
        private long createdAt;
        private boolean expired;
    }

    public String encode(List<Object> sortValues, String fingerprint) {
        return encode(null, sortValues, fingerprint);
    }

    public String encode(String pitId, List<Object> sortValues, String fingerprint) {
        if (sortValues == null || sortValues.isEmpty()) {
            return null;
        }
        try {
            CursorPayload payload = CursorPayload.builder()
                    .pitId(pitId)
                    .sortValues(sortValues)
                    .fingerprint(fingerprint)
                    .createdAt(System.currentTimeMillis())
                    .build();
            String json = objectMapper.writeValueAsString(payload);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Failed to encode search cursor: {}", e.getMessage());
            return null;
        }
    }

    public CursorPayload decode(String cursorString, String expectedFingerprint) {
        if (cursorString == null || cursorString.trim().isEmpty()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(cursorString.trim());
            CursorPayload payload = objectMapper.readValue(bytes, CursorPayload.class);

            if (expectedFingerprint != null && payload.getFingerprint() != null &&
                    !expectedFingerprint.equals(payload.getFingerprint())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Search cursor does not match current query filters.");
            }

            if (System.currentTimeMillis() - payload.getCreatedAt() > CURSOR_TTL_MS) {
                log.info("Search cursor expired; flagging for search restart from beginning per spec");
                payload.setExpired(true);
            }

            return payload;
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.warn("Failed to decode search cursor: {}", e.getMessage());
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Invalid search cursor format.");
        }
    }

    public String computeFingerprint(SearchProductRequest req) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String attrsStr = "";
            if (req.getAttributes() != null && !req.getAttributes().isEmpty()) {
                attrsStr = new TreeMap<>(req.getAttributes()).toString();
            }
            String raw = String.format("q=%s;cat=%s;br=%s;min=%s;max=%s;sort=%s;stock=%s;attrs=%s",
                    req.getQ() != null ? req.getQ().trim() : "",
                    req.getCategoryId() != null ? req.getCategoryId().toString() : "",
                    req.getBrandId() != null ? req.getBrandId().toString() : "",
                    req.getMinPrice() != null ? req.getMinPrice().toString() : "",
                    req.getMaxPrice() != null ? req.getMaxPrice().toString() : "",
                    req.getSort() != null ? req.getSort() : "relevance",
                    req.getInStock() != null ? req.getInStock().toString() : "",
                    attrsStr);
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return "";
        }
    }
}
