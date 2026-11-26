package com.psar.delivery.service;

import com.psar.common.exception.BusinessRuleException;
import com.psar.delivery.entity.DeliveryCompany;
import com.psar.delivery.repository.DeliveryCompanyRepository;
import com.psar.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryCompanyServiceTest extends IntegrationTest {

    @Autowired
    private DeliveryCompanyService deliveryCompanyService;

    @Autowired
    private DeliveryCompanyRepository deliveryCompanyRepository;

    @Test
    void assertActive_activeCompany_passes() {
        DeliveryCompany company = save("J&T Express", true);

        assertThatCode(() -> deliveryCompanyService.assertActive(company.getId())).doesNotThrowAnyException();
    }

    @Test
    void assertActive_inactiveCompany_throws() {
        DeliveryCompany company = save("Old Courier", false);

        assertThatThrownBy(() -> deliveryCompanyService.assertActive(company.getId()))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void assertActive_missingOrNullId_throws() {
        assertThatThrownBy(() -> deliveryCompanyService.assertActive(Long.MAX_VALUE))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> deliveryCompanyService.assertActive(null))
                .isInstanceOf(BusinessRuleException.class);
    }

    private DeliveryCompany save(String name, boolean active) {
        DeliveryCompany company = new DeliveryCompany();
        company.setName(name);
        company.setActive(active);
        return deliveryCompanyRepository.save(company);
    }
}
