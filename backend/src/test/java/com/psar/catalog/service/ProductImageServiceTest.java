package com.psar.catalog.service;

import com.psar.catalog.entity.Product;
import com.psar.catalog.mapper.ProductImageMapper;
import com.psar.catalog.repository.ProductImageRepository;
import com.psar.catalog.repository.ProductRepository;
import com.psar.common.exception.BusinessRuleException;
import com.psar.common.storage.StorageService;
import com.psar.common.storage.StoredFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductImageServiceTest {

    @Mock
    private ProductImageRepository imageRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private ProductImageMapper imageMapper;
    @Mock
    private StorageService storageService;
    @Mock
    private TransactionTemplate transactionTemplate;
    @InjectMocks
    private ProductImageService service;

    @Test
    void upload_saveFailsAfterUpload_deletesUploadedFile() {
        UUID publicId = UUID.randomUUID();
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(7L);
        when(productRepository.findByPublicId(publicId)).thenReturn(Optional.of(product));
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1});
        when(storageService.upload(eq("products"), any())).thenReturn(new StoredFile("products/k", "https://x/k"));
        // e.g. another request filled the 10th slot between the pre-check and the lock
        when(transactionTemplate.execute(any())).thenThrow(new BusinessRuleException("limit"));

        assertThatThrownBy(() -> service.upload(publicId, file)).isInstanceOf(BusinessRuleException.class);

        verify(storageService).deleteAfterCommit("products/k");
    }
}
