package com.psar.notification.mapper;

import com.psar.common.mapper.CentralMapperConfig;
import com.psar.notification.dto.response.NotificationResponse;
import com.psar.notification.entity.Notification;
import org.mapstruct.Mapper;

@Mapper(config = CentralMapperConfig.class)
public interface NotificationMapper {

    NotificationResponse toResponse(Notification notification);
}
