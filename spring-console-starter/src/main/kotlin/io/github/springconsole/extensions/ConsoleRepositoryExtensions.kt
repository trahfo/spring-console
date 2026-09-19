package io.github.springconsole.extensions

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.CrudRepository
import java.util.Optional

/**
 * Convenience extensions for Spring Data repositories inside the REPL.
 *
 * Provides:
 * 1. Overloads accepting [Int] so users can type `todoRepository.findById(2)` or
 *    `todoRepository.getOne(2)` without integer literal type mismatch errors.
 * 2. Eager entity retrieval for `getOne` and `getReferenceById` so uninitialized
 *    Hibernate proxies are never returned to cause [org.hibernate.LazyInitializationException].
 */

fun <T> CrudRepository<T, Long>.findById(id: Int): Optional<T> =
    findById(id.toLong())

fun <T> CrudRepository<T, Long>.deleteById(id: Int) {
    deleteById(id.toLong())
}

fun <T> CrudRepository<T, Long>.existsById(id: Int): Boolean =
    existsById(id.toLong())

fun <T> JpaRepository<T, Long>.getOne(id: Int): T =
    findById(id.toLong()).orElseThrow { NoSuchElementException("No entity found with id $id") }

fun <T> JpaRepository<T, Long>.getOne(id: Long): T =
    findById(id).orElseThrow { NoSuchElementException("No entity found with id $id") }

fun <T> JpaRepository<T, Long>.getReferenceById(id: Int): T =
    findById(id.toLong()).orElseThrow { NoSuchElementException("No entity found with id $id") }

fun <T> JpaRepository<T, Long>.getReferenceById(id: Long): T =
    findById(id).orElseThrow { NoSuchElementException("No entity found with id $id") }
