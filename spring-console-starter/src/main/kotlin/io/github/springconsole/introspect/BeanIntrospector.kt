package io.github.springconsole.introspect

import io.github.springconsole.api.BeanDetails
import io.github.springconsole.api.BeanSummary
import io.github.springconsole.api.MethodSignature
import io.github.springconsole.api.ParameterInfo
import io.github.springconsole.api.PropertyInfo
import io.github.springconsole.binding.BoundBean
import org.springframework.beans.BeanUtils
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Answers the `list_beans` and `inspect_bean` MCP tools from the current
 * binding set, so REPL names, resolved types, and proxy information stay
 * consistent between listing, inspection, and evaluation.
 */
class BeanIntrospector(private val boundBeansSupplier: () -> List<BoundBean>) {

    fun listBeans(packageFilter: String? = null, includeProxies: Boolean = false): List<BeanSummary> =
        boundBeansSupplier()
            .filter { packageFilter.isNullOrBlank() || it.type.name.startsWith(packageFilter) }
            .map { bean ->
                BeanSummary(
                    name = bean.beanName,
                    replName = bean.replName,
                    type = if (includeProxies) bean.instance.javaClass.name else bean.type.name,
                    scope = bean.scope,
                    proxied = bean.proxied,
                )
            }

    /** Looks a bean up by Spring bean name or REPL name; returns null when absent. */
    fun inspectBean(beanName: String): BeanDetails? {
        val bean = boundBeansSupplier().firstOrNull { it.beanName == beanName || it.replName == beanName }
            ?: return null
        return BeanDetails(
            name = bean.beanName,
            replName = bean.replName,
            targetType = bean.type.name,
            runtimeType = bean.instance.javaClass.name,
            proxied = bean.proxied,
            scope = bean.scope,
            interfaces = bean.type.interfaces.map { it.name }.sorted(),
            methods = publicDeclaredMethods(bean.type),
            properties = properties(bean.type),
        )
    }

    private fun publicDeclaredMethods(type: Class<*>): List<MethodSignature> =
        type.methods
            .filter { it.declaringClass != Any::class.java && !it.isSynthetic }
            .sortedWith(compareBy({ it.name }, { it.parameterCount }))
            .map { it.toSignature() }

    private fun Method.toSignature() = MethodSignature(
        name = name,
        parameters = parameters.map { ParameterInfo(it.name, it.parameterizedType.typeName.simplify()) },
        returnType = genericReturnType.typeName.simplify(),
        declaringClass = declaringClass.name,
    )

    private fun properties(type: Class<*>): List<PropertyInfo> =
        BeanUtils.getPropertyDescriptors(type)
            .filter { it.name != "class" }
            .sortedBy { it.name }
            .map {
                PropertyInfo(
                    name = it.name,
                    type = (it.propertyType ?: Any::class.java).name.simplify(),
                    readable = it.readMethod != null && Modifier.isPublic(it.readMethod.modifiers),
                    writable = it.writeMethod != null && Modifier.isPublic(it.writeMethod.modifiers),
                )
            }

    /** `java.util.List<com.example.Todo>` → `List<com.example.Todo>` for common JDK prefixes. */
    private fun String.simplify(): String =
        replace("java.lang.", "").replace("java.util.", "")
}
