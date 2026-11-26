package com.psar.user.mapper;

import com.psar.common.mapper.CentralMapperConfig;
import com.psar.user.dto.response.UserResponse;
import com.psar.user.entity.User;
import org.mapstruct.Mapper;

/** Users are created in the auth service (password hashing, role), so there is no request -> entity method. */
@Mapper(config = CentralMapperConfig.class)
public interface UserMapper {

    UserResponse toResponse(User user);
}
