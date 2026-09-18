package io.github.springconsole.fixture

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@SpringBootApplication
open class ConsoleTestApp

@Entity
open class Note() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    open var title: String = ""

    constructor(title: String) : this() {
        this.title = title
    }

    override fun toString(): String = "Note(id=$id, title='$title')"
}

interface NoteRepository : JpaRepository<Note, Long> {
    fun findByTitle(title: String): List<Note>
}

@Service
open class NoteService(private val repository: NoteRepository) {

    @Transactional
    open fun add(title: String): Note = repository.save(Note(title))

    open fun count(): Long = repository.count()
}
