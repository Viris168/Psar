package com.psar.common.mapper;

import org.mapstruct.MapperConfig;
import org.mapstruct.ReportingPolicy;

/**
 * Shared settings for every MapStruct mapper: use with {@code @Mapper(config = CentralMapperConfig.class)}.
 * An unmapped target field fails the build, so a new column can't be silently skipped.
 * id/createdAt/updatedAt have no setters (BaseEntity), so MapStruct never writes them.
 */
@MapperConfig(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface CentralMapperConfig {
}
