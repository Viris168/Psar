package com.psar.order.controller;

import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.catalog.repository.ProductRepository;
import com.psar.delivery.entity.DeliveryCompany;
import com.psar.delivery.repository.DeliveryCompanyRepository;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.OrderFixtures;
import com.psar.support.TestAuth;
import com.psar.user.entity.Address;
import com.psar.user.entity.User;
import com.psar.user.repository.AddressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CheckoutQuoteControllerTest extends IntegrationTest {

    @Autowired
    private CatalogFixtures catalog;

    @Autowired
    private OrderFixtures orders;

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private DeliveryCompanyRepository deliveryCompanyRepository;

    @Autowired
    private ProductRepository productRepository;

    private User customer;
    private Product rice;
    private Product oil;

    @BeforeEach
    void setUp() {
        customer = orders.customer();
        Category category = catalog.category("Quote groceries");
        rice = catalog.product(category, "Quote rice", "12.50", 20, 0);
        oil = catalog.product(category, "Quote oil", "3.35", 20, 0);
        Map<Product, Integer> lines = new LinkedHashMap<>();
        lines.put(rice, 2);
        lines.put(oil, 3);
        orders.cart(customer, lines);
    }

    @Test
    void quote_cityCodAtTheShop_linesSubtotalFirstBandFeeAndTotal() throws Exception {
        Address address = orders.cityAddress(customer);

        quote(address.getPublicId(), "CASH_ON_DELIVERY")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].productPublicId").value(rice.getPublicId().toString()))
                .andExpect(jsonPath("$.lines[0].name").value("Quote rice"))
                .andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(12.50))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(25.00))
                .andExpect(jsonPath("$.lines[1].lineTotal").value(10.05))
                .andExpect(jsonPath("$.lines[0].id").doesNotExist())
                .andExpect(jsonPath("$.lines[0].productId").doesNotExist())
                .andExpect(jsonPath("$.subtotal").value(35.05))
                .andExpect(jsonPath("$.productDiscount").value(0))
                .andExpect(jsonPath("$.deliveryFee").value(1.00))
                .andExpect(jsonPath("$.total").value(36.05))
                .andExpect(jsonPath("$.deliveryType").value("CITY"))
                .andExpect(jsonPath("$.deliveryCompanyName").value(nullValue()))
                .andExpect(jsonPath("$.paymentMethod").value("CASH_ON_DELIVERY"));
    }

    @Test
    void quote_cityPinTenKmAway_secondBandFee() throws Exception {
        Address address = orders.cityAddress(customer);
        address.setLatitude(new BigDecimal("11.640000"));   // ~9.3 km north of the shop
        addressRepository.save(address);

        quote(address.getPublicId(), "KHQR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryFee").value(2.00))
                .andExpect(jsonPath("$.total").value(37.05));
    }

    @Test
    void quote_cityPinOutsidePhnomPenh_returns422() throws Exception {
        Address address = orders.cityAddress(customer);
        address.setLatitude(new BigDecimal("13.363300"));   // Siem Reap
        address.setLongitude(new BigDecimal("103.856400"));
        addressRepository.save(address);

        quote(address.getPublicId(), "KHQR")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"))
                .andExpect(jsonPath("$.message").value(containsString("outside Phnom Penh")));
    }

    @Test
    void quote_provinceKhqr_companyBaseFeeAndName() throws Exception {
        DeliveryCompany company = orders.deliveryCompany("2.75");
        Address address = orders.provinceAddress(customer, company);

        quote(address.getPublicId(), "KHQR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryFee").value(2.75))
                .andExpect(jsonPath("$.total").value(37.80))
                .andExpect(jsonPath("$.deliveryType").value("PROVINCE"))
                .andExpect(jsonPath("$.deliveryCompanyName").value(company.getName()));
    }

    @Test
    void quote_provinceWithCod_returns422() throws Exception {
        Address address = orders.provinceAddress(customer, orders.deliveryCompany("2.00"));

        quote(address.getPublicId(), "CASH_ON_DELIVERY")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"))
                .andExpect(jsonPath("$.message").value(containsString("KHQR")));
    }

    @Test
    void quote_inactiveCompany_returns422() throws Exception {
        DeliveryCompany company = orders.deliveryCompany("2.00");
        Address address = orders.provinceAddress(customer, company);
        company.setActive(false);
        deliveryCompanyRepository.save(company);

        quote(address.getPublicId(), "KHQR")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("choose another company")));
    }

    @Test
    void quote_priceChangedInCatalog_usesCurrentDbPrice() throws Exception {
        Address address = orders.cityAddress(customer);
        Product changed = productRepository.findById(rice.getId()).orElseThrow();
        changed.setSellPrice(new BigDecimal("10.00"));
        productRepository.save(changed);

        quote(address.getPublicId(), "KHQR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].unitPrice").value(10.00))
                .andExpect(jsonPath("$.subtotal").value(30.05))
                .andExpect(jsonPath("$.total").value(31.05));
    }

    @Test
    void quote_inactiveProductInCart_returns422NamingIt() throws Exception {
        Address address = orders.cityAddress(customer);
        Product inactive = productRepository.findById(oil.getId()).orElseThrow();
        inactive.setActive(false);
        productRepository.save(inactive);

        quote(address.getPublicId(), "KHQR")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("Quote oil")));
    }

    @Test
    void quote_emptyCart_returns422() throws Exception {
        User other = orders.customer();
        Address address = orders.cityAddress(other);

        mockMvc.perform(get("/api/checkout/quote")
                        .param("addressPublicId", address.getPublicId().toString())
                        .param("paymentMethod", "KHQR")
                        .with(TestAuth.as(other)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("empty")));
    }

    @Test
    void quote_otherUsersAddress_returns404() throws Exception {
        Address someoneElses = orders.cityAddress(orders.customer());

        quote(someoneElses.getPublicId(), "KHQR").andExpect(status().isNotFound());
    }

    @Test
    void quote_unknownPaymentMethod_returns400() throws Exception {
        Address address = orders.cityAddress(customer);

        quote(address.getPublicId(), "BITCOIN").andExpect(status().isBadRequest());
    }

    @Test
    void quote_asStaff_returns403() throws Exception {
        mockMvc.perform(get("/api/checkout/quote")
                        .param("addressPublicId", UUID.randomUUID().toString())
                        .param("paymentMethod", "KHQR")
                        .with(TestAuth.asRole(1L, "STAFF")))
                .andExpect(status().isForbidden());
    }

    @Test
    void quote_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/checkout/quote")
                        .param("addressPublicId", UUID.randomUUID().toString())
                        .param("paymentMethod", "KHQR"))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions quote(UUID addressPublicId, String paymentMethod) throws Exception {
        return mockMvc.perform(get("/api/checkout/quote")
                .param("addressPublicId", addressPublicId.toString())
                .param("paymentMethod", paymentMethod)
                .with(TestAuth.as(customer)));
    }
}
