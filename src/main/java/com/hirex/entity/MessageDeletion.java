package com.hirex.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Represents a single user's "Delete for Me" action on a ChatMessage.
 *
 * Replaces the old CSV `deleted_for_user_ids` column on ChatMessage.
 * One row per (message, user) — enforced by a unique constraint so a user
 * can never accumulate duplicate deletion rows, and a message can have at
 * most 2 rows for a one-to-one chat (one per participant).
 *
 * WHY A JOIN TABLE INSTEAD OF A COLUMN ON ChatMessage:
 *  - Indexable & queryable in pure SQL: "give me messages NOT deleted by user X"
 *    is a single NOT EXISTS / LEFT JOIN, instead of fetching every row and
 *    parsing a string in Java on every page load.
 *  - No size limit / truncation risk (the old VARCHAR(500) CSV column could
 *    silently truncate in a chat with many participants or long-lived messages).
 *  - Carries its own audit timestamp (when the user deleted it), which the
 *    CSV approach could not.
 *  - Scales correctly with bulk operations (e.g. "delete entire conversation
 *    for me") via a single bulk INSERT ... SELECT, instead of rewriting N
 *    string columns.
 */
@Entity
@Table(
        name = "message_deletions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_message_deletion_message_user",
                columnNames = {"message_id", "user_id"}
        ),
        indexes = {
                // Used by "is this message deleted for user X" and bulk exclusion-join queries
                @Index(name = "idx_msg_deletion_message_user", columnList = "message_id, user_id"),
                // Used by "list all messages this user has deleted" / cleanup jobs
                @Index(name = "idx_msg_deletion_user", columnList = "user_id")
        }
)
public class MessageDeletion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private ChatMessage message;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "deleted_at", nullable = false)
    private LocalDateTime deletedAt;

    @PrePersist
    public void prePersist() {
        deletedAt = LocalDateTime.now();
    }

    public MessageDeletion() {}

    public MessageDeletion(ChatMessage message, User user) {
        this.message = message;
        this.user = user;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public ChatMessage getMessage() { return message; }
    public void setMessage(ChatMessage message) { this.message = message; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
}