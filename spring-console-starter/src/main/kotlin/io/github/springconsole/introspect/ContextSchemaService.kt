package io.github.springconsole.introspect

import io.github.springconsole.api.AttributeInfo
import io.github.springconsole.api.ContextSchema
import io.github.springconsole.api.EntityInfo
import io.github.springconsole.api.RepositoryInfo
import io.github.springconsole.api.ServiceInfo
import io.github.springconsole.binding.BoundBean
import org.slf4j.LoggerFactory
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.ResolvableType
import org.springframework.util.ClassUtils

/**
 * Builds the `get_context_schema` payload: JPA entities, Spring Data
 * repositories, and `@Service` beans. JPA and Spring Data are optional
 * dependencies of the host application, so each section degrades to an empty
 * list when the corresponding library is absent.
 */
class ContextSchemaService(
    private val context: ConfigurableApplicationContext,
    private val boundBeansSupplier: () -> List<BoundBean>,
) {
    private val log = LoggerFactory.getLogger(ContextSchemaService::class.java)

    fun schema(): ContextSchema {
        val bound = boundBeansSupplier()
        return ContextSchema(
            entities = jpaEntities(),
            repositories = repositories(bound),
            services = services(bound),
        )
    }

    private fun jpaEntities(): List<EntityInfo> {
        val classLoader = context.classLoader ?: javaClass.classLoader
        if (!ClassUtils.isPresent("jakarta.persistence.EntityManagerFactory", classLoader)) return emptyList()
        return try {
            JpaEntityReader.read(context)
        } catch (e: Exception) {
            log.debug("Could not read JPA metamodel", e)
            emptyList()
        }
    }

    private fun repositories(bound: List<BoundBean>): List<RepositoryInfo> {
        val classLoader = context.classLoader ?: javaClass.classLoader
        if (!ClassUtils.isPresent("org.springframework.data.repository.Repository", classLoader)) return emptyList()
        val repositoryMarker = try {
            ClassUtils.forName("org.springframework.data.repository.Repository", classLoader)
        } catch (e: Exception) {
            return emptyList()
        }

        return bound
            .filter { repositoryMarker.isAssignableFrom(it.type) }
            .map { bean ->
                RepositoryInfo(
                    beanName = bean.beanName,
                    replName = bean.replName,
                    type = bean.type.name,
                    domainType = ResolvableType.forClass(bean.type).`as`(repositoryMarker)
                        .getGeneric(0).resolve()?.name,
                    methods = userDeclaredRepositoryMethods(bean.type),
                )
            }
            .sortedBy { it.type }
    }

    /**
     * Query methods declared on the user's repository interface hierarchy;
     * framework base interfaces (CrudRepository & co.) are summarized away.
     */
    private fun userDeclaredRepositoryMethods(type: Class<*>): List<String> =
        type.methods
            .filter { method ->
                !method.isSynthetic &&
                    !method.declaringClass.name.startsWith("org.springframework.") &&
                    !method.declaringClass.name.startsWith("java.")
            }
            .map { method ->
                val params = method.parameters.joinToString(", ") { it.parameterizedType.typeName.substringAfterLast('.') }
                "${method.name}($params): ${method.genericReturnType.typeName.substringAfterLast('.')}"
            }
            .sorted()

    private fun services(bound: List<BoundBean>): List<ServiceInfo> =
        bound
            .filter { bean ->
                context.beanFactory.findAnnotationOnBean(
                    bean.beanName,
                    org.springframework.stereotype.Service::class.java,
                ) != null
            }
            .map { ServiceInfo(beanName = it.beanName, replName = it.replName, type = it.type.name) }
            .sortedBy { it.type }
}

/**
 * Isolated so `jakarta.persistence` classes are only loaded when the host
 * application actually ships JPA.
 */
private object JpaEntityReader {
    fun read(context: ConfigurableApplicationContext): List<EntityInfo> {
        val emf = context.getBean(jakarta.persistence.EntityManagerFactory::class.java)
        return emf.metamodel.entities
            .map { entity ->
                EntityInfo(
                    name = entity.name,
                    type = entity.javaType.name,
                    attributes = entity.attributes
                        .map { attribute ->
                            AttributeInfo(
                                name = attribute.name,
                                type = attribute.javaType.simpleName,
                                id = attribute is jakarta.persistence.metamodel.SingularAttribute<*, *> && attribute.isId,
                            )
                        }
                        .sortedBy { it.name }
                        .sortedByDescending { it.id },
                )
            }
            .sortedBy { it.name }
    }
}
