package com.psar.user.mapper;

import com.psar.common.mapper.CentralMapperConfig;
import com.psar.user.dto.response.UserResponse;
import com.psar.user.entity.User;
import com.psar.user.entity.UserProfile;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Users are created in the auth service (password hashing, role), so there is no request -> entity method. */
@Mapper(config = CentralMapperConfig.class)
public interface UserMapper {

    /** {@code profile} may be null (fullName/position are then null). */
    @Mapping(target = "publicId", source = "user.publicId")
    @Mapping(target = "createdAt", source = "user.createdAt")
    @Mapping(target = "fullName", source = "profile.fullName")
    @Mapping(target = "position", source = "profile.position")
    UserResponse toResponse(User user, UserProfile profile);
}
