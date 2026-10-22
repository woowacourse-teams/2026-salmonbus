package com.gustler.backend.api.support;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.ClassDescriptor;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.ClassOrdererContext;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.test.context.BootstrapWith;

/**
 * 같은 스프링 테스트 컨텍스트를 쓰는 시험 클래스를 이어서 실행한다.
 *
 * <p>컨텍스트 캐시를 작게 두면 다른 컨텍스트를 쓰는 클래스가 사이에 끼는 순간 앞 컨텍스트가 닫히고, 다시 그 컨텍스트를
 * 쓰는 클래스가 오면 새로 띄운다. 캐시 키({@code MergedContextConfiguration})가 같은 클래스끼리 묶어 두면 컨텍스트마다
 * 한 번만 띄운다. 묶음은 묶음 안에서 가장 앞선 클래스 이름 순으로, 묶음 안은 클래스 이름 순으로 둔다. 스프링 시험이
 * 아니거나 키를 만들 수 없는 클래스는 혼자 한 묶음이다.
 *
 * <p>컨텍스트를 띄우지 않고 설정만 모은다. 실행 순서만 바꾸며 시험 내용에는 손대지 않는다.
 */
public final class ContextGroupingClassOrderer implements ClassOrderer {
    @Override
    public void orderClasses(ClassOrdererContext context) {
        Map<ClassDescriptor, Object> keys = new HashMap<>();
        Map<Object, String> firstNames = new HashMap<>();
        for (ClassDescriptor descriptor : context.getClassDescriptors()) {
            Object key = contextKey(descriptor.getTestClass());
            keys.put(descriptor, key);
            firstNames.merge(key, descriptor.getTestClass().getName(), ContextGroupingClassOrderer::earlier);
        }
        context.getClassDescriptors().sort(Comparator
            .comparing((ClassDescriptor descriptor) -> firstNames.get(keys.get(descriptor)))
            .thenComparing(descriptor -> descriptor.getTestClass().getName()));
    }

    private static Object contextKey(Class<?> testClass) {
        if (!MergedAnnotations.from(testClass, SearchStrategy.TYPE_HIERARCHY).isPresent(BootstrapWith.class)) {
            return testClass;
        }
        try {
            return BootstrapUtils.resolveTestContextBootstrapper(testClass).buildMergedContextConfiguration();
        } catch (RuntimeException exception) {
            return testClass;
        }
    }

    private static String earlier(String first, String second) {
        if (first.compareTo(second) <= 0) {
            return first;
        }
        return second;
    }
}
