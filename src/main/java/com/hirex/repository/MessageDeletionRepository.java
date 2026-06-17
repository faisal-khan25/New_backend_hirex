package com.hirex.repository;

import com.hirex.entity.ChatMessage;
import com.hirex.entity.MessageDeletion;
import com.hirex.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

public interface MessageDeletionRepository extends JpaRepository<MessageDeletion, Long> {

    // Used by deleteForMe() to make the operation idempotent — re-deleting
    // an already-deleted message for the same user is a no-op instead of
    // hitting the unique constraint and throwing.
    boolean existsByMessageAndUser(ChatMessage message, User user);

    // OPTIMIZED: Single query to find which of a batch of message IDs are
    // already deleted-for-me by this user. Used by getMessages() to filter
    // a page of messages in SQL-adjacent fashion instead of N existence checks.
    @Query("SELECT d.message.id FROM MessageDeletion d " +
            "WHERE d.user = :user AND d.message.id IN :messageIds")
    Set<Long> findDeletedMessageIdsForUser(
            @Param("user") User user,
            @Param("messageIds") List<Long> messageIds);

    @Modifying
    @Transactional
    void deleteByMessageAndUser(ChatMessage message, User user);

    // Bulk cleanup: if a message is ever hard-deleted (not part of this
    // feature, but useful for retention jobs), this removes orphaned rows.
    @Modifying
    @Transactional
    long deleteByMessage(ChatMessage message);
}