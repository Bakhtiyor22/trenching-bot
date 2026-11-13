package com.trenching.bot.support

import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.boot.test.context.TestComponent
import org.springframework.core.type.classreading.MetadataReader
import org.springframework.core.type.classreading.MetadataReaderFactory

/**
 * Keeps `@TestConfiguration` classes out of an application a test starts by hand. The test framework registers such a filter
 * for its own contexts only, so without this one the application's component scan would pick up the test database
 * configurations and start a second database.
 */
class TestComponentExcludeFilter : TypeExcludeFilter() {
    override fun match(metadataReader: MetadataReader, metadataReaderFactory: MetadataReaderFactory): Boolean =
        metadataReader.annotationMetadata.isAnnotated(TestComponent::class.java.name)

    override fun equals(other: Any?): Boolean = other is TestComponentExcludeFilter

    override fun hashCode(): Int = TestComponentExcludeFilter::class.java.hashCode()
}
