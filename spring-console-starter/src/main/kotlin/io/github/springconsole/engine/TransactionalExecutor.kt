package io.github.springconsole.engine

import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.DefaultTransactionDefinition

/**
 * Wraps snippet execution in a fresh Spring transaction that is
 * unconditionally rolled back on completion (FR-2), so agents can "try out"
 * mutations without leaving any trace in the database.
 */
class TransactionalExecutor(private val transactionManager: PlatformTransactionManager?) {

    val rollbackSupported: Boolean
        get() = transactionManager != null

    /**
     * Runs [block], inside a new rollback-only transaction when [rollback] is
     * requested and a [PlatformTransactionManager] is available.
     *
     * @return the block result plus whether a rollback actually happened.
     */
    fun <T> execute(rollback: Boolean, block: () -> T): Sandboxed<T> {
        if (!rollback || transactionManager == null) {
            return Sandboxed(block(), rolledBack = false)
        }

        val definition = DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW).apply {
            setName("spring-console-sandbox")
        }
        val status = transactionManager.getTransaction(definition)
        try {
            return Sandboxed(block(), rolledBack = true)
        } finally {
            // Unconditional rollback (FR-2.2): success and failure both revert.
            transactionManager.rollback(status)
        }
    }

    data class Sandboxed<T>(val value: T, val rolledBack: Boolean)
}
