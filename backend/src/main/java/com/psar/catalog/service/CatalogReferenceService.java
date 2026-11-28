package com.psar.catalog.service;

import com.psar.catalog.dto.response.CategoryReference;
import com.psar.catalog.dto.response.ProductReference;
import com.psar.catalog.repository.CategoryRepository;
import com.psar.catalog.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Looks up products and categories by their public identifiers or internal ids, for modules that store ids
 * (promotion targets). Active and inactive alike; missing ones are simply not in the result.
 */
@Service
@RequiredArgsConstructor
public class CatalogReferenceService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public List<ProductReference> productsByPublicIds(Collection<UUID> publicIds) {
        return publicIds.isEmpty() ? List.of() : productRepository.findReferencesByPublicIdIn(publicIds);
    }

    @Transactional(readOnly = true)
    public List<ProductReference> productsByIds(Collection<Long> ids) {
        return ids.isEmpty() ? List.of() : productRepository.findReferencesByIdIn(ids);
    }

    @Transactional(readOnly = true)
    public List<CategoryReference> categoriesBySlugs(Collection<String> slugs) {
        return slugs.isEmpty() ? List.of() : categoryRepository.findReferencesBySlugIn(slugs);
    }

    @Transactional(readOnly = true)
    public List<CategoryReference> categoriesByIds(Collection<Long> ids) {
        return ids.isEmpty() ? List.of() : categoryRepository.findReferencesByIdIn(ids);
    }
}
