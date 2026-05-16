package com.olive.commerce.search;

import com.olive.commerce.product.Product;
import com.olive.commerce.product.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code ./gradlew reindexProducts}용 일회성 ApplicationRunner.
 *
 * <p>{@code reindex} 프로필이 활성화된 부팅에서만 동작 — 일반 부팅을 오염시키지
 * 않는다. Gradle task가 부팅 시 {@code --spring.profiles.active=local,reindex}로
 * 전달.
 *
 * <p>전체 {@code products} 테이블을 100건씩 페이지네이션 + bulk index. 끝나면
 * 같은 컨텍스트를 정상 종료해 task를 끝낸다.
 */
@Component
@Profile("reindex")
public class ReindexProductsCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReindexProductsCommand.class);

    private final ProductRepository productRepository;
    private final ProductIndexer productIndexer;
    private final SearchIndexInitializer indexInitializer;
    private final ApplicationContext applicationContext;

    public ReindexProductsCommand(
        ProductRepository productRepository,
        ProductIndexer productIndexer,
        SearchIndexInitializer indexInitializer,
        ApplicationContext applicationContext
    ) {
        this.productRepository = productRepository;
        this.productIndexer = productIndexer;
        this.indexInitializer = indexInitializer;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("Reindex starting: ensuring index exists...");
        indexInitializer.ensureProductsIndex();

        long total = productRepository.count();
        log.info("Reindex scanning {} products in batches of {}", total, ProductIndexer.BULK_SIZE);

        int indexed = 0;
        int pageNumber = 0;
        while (true) {
            List<Product> page = productRepository.findAll(
                PageRequest.of(
                    pageNumber,
                    ProductIndexer.BULK_SIZE,
                    Sort.by(Sort.Direction.ASC, "id")
                )
            ).getContent();
            if (page.isEmpty()) break;

            List<Long> ids = new ArrayList<>(page.size());
            for (Product p : page) ids.add(p.getId());
            productIndexer.indexBulk(ids);
            indexed += ids.size();
            pageNumber++;
            log.info("Reindex progress: {}/{}", indexed, total);
        }

        log.info("Reindex completed: {} products indexed.", indexed);
        // 정상 종료 — task가 끝났음을 Gradle에 알린다.
        SpringApplication.exit(applicationContext, () -> 0);
    }
}
