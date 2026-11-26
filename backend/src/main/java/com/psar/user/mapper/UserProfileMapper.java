package com.psar.user.mapper;

import com.psar.common.mapper.CentralMapperConfig;
import com.psar.user.dto.request.UpdateProfileRequest;
import com.psar.user.dto.response.ProfileResponse;
import com.psar.user.entity.UserProfile;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(config = CentralMapperConfig.class)
public interface UserProfileMapper {

    /** Reads the lazy user: call inside a transaction. */
    @Mapping(target = "publicId", source = "user.publicId")
    @Mapping(target = "email", source = "user.email")
    @Mapping(target = "role", source = "user.role")
    @Mapping(target = "emailVerified", source = "user.emailVerified")
    ProfileResponse toResponse(UserProfile profile);

    /** Customers can't change their own position (staff only) or points balance. */
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "position", ignore = true)
    @Mapping(target = "pointsBalance", ignore = true)
    void update(UpdateProfileRequest request, @MappingTarget UserProfile profile);
}
