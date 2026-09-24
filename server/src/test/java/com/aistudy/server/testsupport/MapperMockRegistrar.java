package com.aistudy.server.testsupport;

import org.apache.ibatis.annotations.Mapper;
import org.mockito.Mockito;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.util.ClassUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Supplies a Mockito mock for every {@code @Mapper} interface so the
 * DataSource-excluded {@code test} profile can boot the real context.
 *
 * <p>Import it on a full-context test instead of hand-listing
 * {@code @MockitoBean} fields per mapper. Hand-maintained lists were exactly
 * why {@code contextLoads()} stopped covering the application it is named
 * after: every new production mapper had to be added to every such test by
 * hand, and the AI-batch / note / folder-import mappers were not.
 *
 * <p>A test-local {@code @MockitoBean} of the same type still wins, so
 * existing stubbing needs no change.
 */
public class MapperMockRegistrar implements BeanDefinitionRegistryPostProcessor, PriorityOrdered {

    private static final String MAPPER_SCAN_PATTERN = "classpath*:com/aistudy/server/**/*Mapper.class";

    /**
     * Ahead of {@code MockitoPostProcessor} (lowest precedence) so a test's own
     * {@code @MockitoBean} finds an existing definition to replace instead of
     * registering a second mock of the same type.
     */
    private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        ClassLoader classLoader = getClass().getClassLoader();
        for (Class<?> mapper : findMapperInterfaces(classLoader)) {
            registerMock(registry, mapper);
        }
        // Services that reach for JdbcTemplate directly (provenance reads) are
        // not @Mapper types, but they still cannot exist without a DataSource.
        registerMock(registry, org.springframework.jdbc.core.JdbcTemplate.class);
    }

    private void registerMock(BeanDefinitionRegistry registry, Class<?> type) {
        String beanName = beanName(type);
        if (registry.containsBeanDefinition(beanName)) {
            return;
        }
        RootBeanDefinition definition = new RootBeanDefinition(type);
        definition.setInstanceSupplier(() -> Mockito.mock(type));
        registry.registerBeanDefinition(beanName, definition);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // Registration only.
    }

    private List<Class<?>> findMapperInterfaces(ClassLoader classLoader) {
        List<Class<?>> found = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            SimpleMetadataReaderFactory readerFactory = new SimpleMetadataReaderFactory(resolver);
            for (Resource resource : resolver.getResources(MAPPER_SCAN_PATTERN)) {
                var metadata = readerFactory.getMetadataReader(resource).getAnnotationMetadata();
                if (metadata.isAnnotated(Mapper.class.getName())) {
                    found.add(ClassUtils.resolveClassName(metadata.getClassName(), classLoader));
                }
            }
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "Could not scan @Mapper interfaces for the test profile", ex);
        }
        return found;
    }

    private String beanName(Class<?> mapper) {
        String simple = mapper.getSimpleName();
        return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
    }
}
