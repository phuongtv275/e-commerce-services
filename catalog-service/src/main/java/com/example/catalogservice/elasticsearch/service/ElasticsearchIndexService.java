package com.example.catalogservice.elasticsearch.service;

import com.example.catalogservice.elasticsearch.document.ProductSkuDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.index.AliasAction;
import org.springframework.data.elasticsearch.core.index.AliasActionParameters;
import org.springframework.data.elasticsearch.core.index.AliasActions;
import org.springframework.data.elasticsearch.core.index.AliasData;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ElasticsearchIndexService {

    private final ElasticsearchOperations elasticsearchOperations;

    @Value("${catalog.index.name:product_catalog}")
    private String defaultAliasName;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        initAliasIfNeeded(defaultAliasName);
    }

    public void initAliasIfNeeded(String aliasName) {
        try {
            IndexCoordinates aliasCoords = IndexCoordinates.of(aliasName);
            IndexOperations indexOps = elasticsearchOperations.indexOps(aliasCoords);

            if (!indexOps.exists()) {
                String physicalIndex = aliasName + "-v1";
                createPhysicalIndex(physicalIndex);

                AliasAction.Add addAction = new AliasAction.Add(AliasActionParameters.builder()
                        .withIndices(physicalIndex)
                        .withAliases(aliasName)
                        .build());
                indexOps.alias(new AliasActions(addAction));
                log.info("Successfully initialized Elasticsearch index {} with alias {}", physicalIndex, aliasName);
            }
        } catch (Exception e) {
            log.warn("Elasticsearch alias initialization skipped or deferred: {}", e.getMessage());
        }
    }

    public void createPhysicalIndex(String physicalIndex) {
        IndexCoordinates coords = IndexCoordinates.of(physicalIndex);
        IndexOperations ops = elasticsearchOperations.indexOps(coords);
        if (!ops.exists()) {
            ops.create();
            ops.putMapping(ProductSkuDocument.class);
            log.info("Created physical Elasticsearch index: {}", physicalIndex);
        }
    }

    public void swapAlias(String aliasName, String oldPhysicalIndex, String newPhysicalIndex) {
        IndexCoordinates aliasCoords = IndexCoordinates.of(aliasName);
        IndexOperations indexOps = elasticsearchOperations.indexOps(aliasCoords);

        AliasAction.Add add = new AliasAction.Add(AliasActionParameters.builder()
                .withIndices(newPhysicalIndex)
                .withAliases(aliasName)
                .build());

        AliasActions actions;
        if (oldPhysicalIndex != null && !oldPhysicalIndex.trim().isEmpty() && !oldPhysicalIndex.equals(newPhysicalIndex)) {
            AliasAction.Remove remove = new AliasAction.Remove(AliasActionParameters.builder()
                    .withIndices(oldPhysicalIndex)
                    .withAliases(aliasName)
                    .build());
            actions = new AliasActions(remove, add);
        } else {
            actions = new AliasActions(add);
        }

        indexOps.alias(actions);
        log.info("Atomically swapped alias {} from [{}] to [{}]", aliasName, oldPhysicalIndex, newPhysicalIndex);
    }

    public String getCurrentPhysicalIndex(String aliasName) {
        try {
            IndexCoordinates aliasCoords = IndexCoordinates.of(aliasName);
            IndexOperations indexOps = elasticsearchOperations.indexOps(aliasCoords);
            Map<String, Set<AliasData>> aliases = indexOps.getAliases(aliasName);
            if (aliases != null && !aliases.isEmpty()) {
                return aliases.keySet().iterator().next();
            }
        } catch (Exception e) {
            log.warn("Could not query current physical index for alias {}: {}", aliasName, e.getMessage());
        }
        return null;
    }

    public void deletePhysicalIndex(String physicalIndex) {
        try {
            IndexCoordinates coords = IndexCoordinates.of(physicalIndex);
            IndexOperations ops = elasticsearchOperations.indexOps(coords);
            if (ops.exists()) {
                ops.delete();
                log.info("Deleted physical index: {}", physicalIndex);
            }
        } catch (Exception e) {
            log.warn("Could not delete physical index {}: {}", physicalIndex, e.getMessage());
        }
    }
}
