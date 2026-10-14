package app.site.core.model

import java.time.Instant
import java.time.LocalDate

/** Problems on site (S31), with comments. Mirrors issues and comments in the schema. */
enum class IssueStatus { OPEN, CLOSED }

data class Issue(
    val id: String,
    val projectId: String,
    /** Per-project number, assigned by the server. Null while pending. */
    val number: Int?,
    val title: String,
    val description: String? = null,
    val taskId: String? = null,
    val dueDate: LocalDate? = null,
    val assigneeId: String? = null,
    val status: IssueStatus = IssueStatus.OPEN,
    val raisedBy: String? = null,
    val createdAt: Instant,
    val closedAt: Instant? = null,
    val commentCount: Int = 0,
) {
    fun isOverdue(today: LocalDate) = status == IssueStatus.OPEN && dueDate != null && dueDate.isBefore(today)
}

data class NewIssue(
    val projectId: String,
    val title: String,
    val description: String? = null,
    val taskId: String? = null,
    val dueDate: LocalDate? = null,
    val assigneeId: String? = null,
)

data class Comment(val id: String, val issueId: String, val authorId: String?, val body: String, val createdAt: Instant)
