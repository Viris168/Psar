package com.psar.catalog.repository;

import com.psar.catalog.dto.response.CategoryReference;
import com.psar.catalog.entity.Category;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /** SELECT … FOR UPDATE: editing or deactivating a category waits for products being saved into it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Category c where c.slug = :slug")
    Optional<Category> findForUpdateBySlug(@Param("slug") String slug);

    /**
     * SELECT … FOR SHARE: saving a product into a category. Products don't block each other, but a
     * concurrent deactivation waits (and then sees the new product), or runs first (and this sees inactive).
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select c from Category c where c.slug = :slug")
    Optional<Category> findForShareBySlug(@Param("slug") String slug);

    /** Admin list: parent fetched in the same query (its slug is in the response). */
    @Override
    @EntityGraph(attributePaths = "parent")
    Page<Category> findAll(Pageable pageable);

    boolean existsByParentId(Long parentId);

    boolean existsByParentIdAndActiveTrue(Long parentId);

    /** Storefront tree: only small, so one unpaginated query. */
    @EntityGraph(attributePaths = "parent")
    List<Category> findAllByActiveTrue(Sort sort);

    @Query("select c.id from Category c where c.parent.id = :parentId")
    List<Long> findIdsByParentId(@Param("parentId") Long parentId);

    @Query("""
            select new com.psar.catalog.dto.response.CategoryReference(c.id, c.slug, c.name)
            from Category c where c.slug in :slugs""")
    List<CategoryReference> findReferencesBySlugIn(@Param("slugs") Collection<String> slugs);

    @Query("""
            select new com.psar.catalog.dto.response.CategoryReference(c.id, c.slug, c.name)
            from Category c where c.id in :ids""")
    List<CategoryReference> findReferencesByIdIn(@Param("ids") Collection<Long> ids);
}
