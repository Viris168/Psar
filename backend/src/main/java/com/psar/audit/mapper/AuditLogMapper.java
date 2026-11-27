package com.psar.audit.mapper;

import com.psar.audit.dto.response.AuditActorResponse;
import com.psar.audit.dto.response.AuditLogResponse;
import com.psar.audit.entity.AuditLog;
import com.psar.common.mapper.CentralMapperConfig;
import com.psar.user.dto.response.UserSummary;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = CentralMapperConfig.class)
public interface AuditLogMapper {

    @Mapping(target = "publicId", source = "log.publicId")
    @Mapping(target = "actor", source = "actor")
    AuditLogResponse toResponse(AuditLog log, UserSummary actor);

    AuditActorResponse toActor(UserSummary user);
}
