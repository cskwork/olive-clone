package com.olive.commerce.search;

import com.olive.commerce.product.Product;
import com.olive.commerce.product.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.stream.LongStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReindexProductsCommandTest {

    @Test
    void indexesShortPageOnlyOnce() throws Exception {
        ProductRepository productRepository = mock(ProductRepository.class);
        ProductIndexer productIndexer = mock(ProductIndexer.class);
        SearchIndexInitializer indexInitializer = mock(SearchIndexInitializer.class);
        GenericApplicationContext context = new GenericApplicationContext();
        context.refresh();

        List<Product> products = LongStream.rangeClosed(1, 13)
            .mapToObj(this::productWithId)
            .toList();

        when(productRepository.count()).thenReturn(13L);
        when(productRepository.findAll(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(0);
            if (pageable.getPageNumber() == 0) {
                return new PageImpl<>(products, pageable, 13);
            }
            return Page.empty(pageable);
        });

        try {
            ReindexProductsCommand command = new ReindexProductsCommand(
                productRepository,
                productIndexer,
                indexInitializer,
                context
            );

            command.run(mock(ApplicationArguments.class));
        } finally {
            if (context.isActive()) {
                context.close();
            }
        }

        verify(indexInitializer).ensureProductsIndex();
        verify(productIndexer).indexBulk(LongStream.rangeClosed(1, 13).boxed().toList());
        verify(productIndexer, times(1)).indexBulk(anyList());
    }

    private Product productWithId(long id) {
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(id);
        return product;
    }
}
