package com.psar.inventory.mapper;

import com.psar.catalog.dto.response.ProductStockInfo;
import com.psar.common.mapper.CentralMapperConfig;
import com.psar.inventory.dto.response.InventoryProductResponse;
import com.psar.inventory.dto.response.LowStockResponse;
import com.psar.inventory.dto.response.StockMovementResponse;
import com.psar.inventory.entity.StockMovement;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** ProductStockInfo.id is internal and is never copied into a response. */
@Mapper(config = CentralMapperConfig.class)
public interface StockMapper {

    InventoryProductResponse toProduct(ProductStockInfo info);

    LowStockResponse toLowStock(ProductStockInfo info);

    @Mapping(target = "product", source = "product")
    @Mapping(target = "quantity", source = "movement.quantity")
    @Mapping(target = "referenceNo", source = "referenceNo")
    StockMovementResponse toResponse(StockMovement movement, InventoryProductResponse product, String referenceNo);
}
