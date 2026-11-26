package com.psar.delivery.service;

import com.psar.common.exception.BusinessRuleException;
import com.psar.common.exception.NotFoundException;
import com.psar.common.web.PageResponse;
import com.psar.delivery.dto.response.DeliveryCompanyResponse;
import com.psar.delivery.entity.DeliveryCompany;
import com.psar.delivery.repository.DeliveryCompanyRepository;
import com.psar.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void deleteDeliveryCompany_softDeletes() {
        DeliveryCompany company = save("Soon Gone", true);

        deliveryCompanyService.delete(company.getId());

        DeliveryCompany reloaded = deliveryCompanyRepository.findById(company.getId()).orElseThrow();
        assertThat(reloaded.isActive()).isFalse();
        assertThatThrownBy(() -> deliveryCompanyService.assertActive(company.getId()))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void delete_missingCompany_throwsNotFound() {
        assertThatThrownBy(() -> deliveryCompanyService.delete(Long.MAX_VALUE))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void listActive_hugePageSize_capsAt100AndExcludesInactive() {
        DeliveryCompany inactive = save("Inactive Listing", false);

        PageResponse<DeliveryCompanyResponse> page = deliveryCompanyService.listActive(
                PageRequest.of(0, 1000, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(page.size()).isEqualTo(100);
        assertThat(page.content()).allMatch(DeliveryCompanyResponse::active)
                .extracting(DeliveryCompanyResponse::id).doesNotContain(inactive.getId());
    }

    private DeliveryCompany save(String name, boolean active) {
        DeliveryCompany company = new DeliveryCompany();
        company.setName(name);
        company.setActive(active);
        return deliveryCompanyRepository.save(company);
    }
}
