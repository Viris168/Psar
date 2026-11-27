package com.psar.inventory.mapper;

import com.psar.common.mapper.CentralMapperConfig;
import com.psar.inventory.dto.response.InventoryProductResponse;
import com.psar.inventory.dto.response.PurchaseOrderDetailResponse;
import com.psar.inventory.dto.response.PurchaseOrderItemResponse;
import com.psar.inventory.dto.response.PurchaseOrderResponse;
import com.psar.inventory.dto.response.SupplierSummary;
import com.psar.inventory.entity.PurchaseOrder;
import com.psar.inventory.entity.PurchaseOrderItem;
import com.psar.inventory.entity.Supplier;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(config = CentralMapperConfig.class)
public interface PurchaseOrderMapper {

    PurchaseOrderResponse toResponse(PurchaseOrder purchaseOrder);

    /** {@code lines} already carry the product names (looked up through StockService). */
    @Mapping(target = "items", source = "lines")
    PurchaseOrderDetailResponse toDetailResponse(PurchaseOrder purchaseOrder, List<PurchaseOrderItemResponse> lines);

    @Mapping(target = "product", source = "product")
    @Mapping(target = "quantity", source = "item.quantity")
    @Mapping(target = "unitCost", source = "item.unitCost")
    @Mapping(target = "lineTotal", expression = "java(item.lineTotal())")
    PurchaseOrderItemResponse toItemResponse(PurchaseOrderItem item, InventoryProductResponse product);

    SupplierSummary toSupplierSummary(Supplier supplier);
}
