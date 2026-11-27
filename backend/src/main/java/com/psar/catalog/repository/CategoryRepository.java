package com.psar.catalog.repository;

import com.psar.catalog.entity.Category;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findBySlug(String slug);

    boolean existsBySlug(String slug);

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
}
