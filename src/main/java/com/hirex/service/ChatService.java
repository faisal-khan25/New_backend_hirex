package com.hirex.service;

import com.hirex.dto.ChatDto;
import com.hirex.entity.*;
import com.hirex.exception.ChatAccessDeniedException;
import com.hirex.exception.DeleteWindowExpiredException;
import com.hirex.exception.ResourceNotFoundException;
import com.hirex.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ChatService {

    // Default page size for paginated message loading (infinite scroll)
    private static final int DEFAULT_PAGE_SIZE = 30;

    // Configurable "Delete for Everyone" time window (minutes after sending).
    // Externalized via application.properties so it can be tuned per
    // environment without a code change/redeploy:
    //   chat.delete-for-everyone.window-minutes=60
    @Value("${chat.delete-for-everyone.window-minutes:60}")
    private long deleteForEveryoneWindowMinutes;

    private static final String DELETED_MESSAGE_PLACEHOLDER = "This message was deleted";

    private final ChatMessageRepository chatRepo;
    private final ApplicationRepository appRepo;
    private final UserRepository userRepo;
    private final MessageReactionRepository reactionRepo;
    private final MessageDeletionRepository deletionRepo;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "jpg", "jpeg", "png", "gif", "zip", "txt", "xls", "xlsx"
    );

    private static final Map<String, String> EXT_TO_TYPE = new HashMap<>();
    static {
        for (String e : List.of("jpg","jpeg","png","gif","webp")) EXT_TO_TYPE.put(e, "IMAGE");
        for (String e : List.of("pdf"))                           EXT_TO_TYPE.put(e, "PDF");
        for (String e : List.of("doc","docx"))                   EXT_TO_TYPE.put(e, "DOC");
        for (String e : List.of("zip","tar","gz"))               EXT_TO_TYPE.put(e, "ZIP");
        for (String e : List.of("xls","xlsx"))                   EXT_TO_TYPE.put(e, "EXCEL");
    }

    public ChatService(ChatMessageRepository chatRepo,
                       ApplicationRepository appRepo,
                       UserRepository userRepo,
                       MessageReactionRepository reactionRepo,
                       MessageDeletionRepository deletionRepo) {
        this.chatRepo     = chatRepo;
        this.appRepo      = appRepo;
        this.userRepo     = userRepo;
        this.reactionRepo = reactionRepo;
        this.deletionRepo = deletionRepo;
    }

    // ── Send text message ────────────────────────────────────────

    @Transactional
    public ChatDto.MessageResponse sendMessage(Long applicationId, String content, String senderEmail) {
        User sender = userRepo.findByEmail(senderEmail).orElseThrow(() -> new RuntimeException("User not found"));
        Application app = appRepo.findById(applicationId).orElseThrow(() -> new RuntimeException("Application not found"));
        validateAccess(app, sender);
        checkCandidateEligibility(app, sender);

        ChatMessage msg = new ChatMessage();
        msg.setApplication(app);
        msg.setSender(sender);
        msg.setContent(content.trim());
        chatRepo.save(msg);

        ChatDto.MessageResponse response = toMessageResponse(msg, sender.getId());

        // Push new message to all conversation subscribers via WebSocket.
        // This allows the frontend to stop polling and only rely on WS for real-time updates.
        messagingTemplate.convertAndSend(
                "/topic/chat/" + applicationId,
                Map.of("type", "NEW_MESSAGE", "message", response)
        );

        return response;
    }

    // ── Send file message ────────────────────────────────────────

    @Transactional
    public ChatDto.MessageResponse sendFile(Long applicationId, MultipartFile file, String senderEmail) throws IOException {
        User sender = userRepo.findByEmail(senderEmail).orElseThrow(() -> new RuntimeException("User not found"));
        Application app = appRepo.findById(applicationId).orElseThrow(() -> new RuntimeException("Application not found"));
        validateAccess(app, sender);
        checkCandidateEligibility(app, sender);

        String originalName = file.getOriginalFilename();
        String ext = (originalName != null && originalName.contains("."))
                ? originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase()
                : "";
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new RuntimeException("File type not allowed: " + ext);
        }

        String uploadDir = System.getProperty("chat.upload.dir",
                System.getProperty("user.home") + "/chat-uploads");
        Path dir = Paths.get(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        String storedName = UUID.randomUUID() + "_" + originalName;
        Path dest = dir.resolve(storedName);
        file.transferTo(dest.toFile());

        String fileUrl  = "/api/chat/files/" + storedName;
        String fileType = EXT_TO_TYPE.getOrDefault(ext, "OTHER");

        ChatMessage msg = new ChatMessage();
        msg.setApplication(app);
        msg.setSender(sender);
        msg.setContent(null);
        msg.setFileUrl(fileUrl);
        msg.setFileName(originalName);
        msg.setFileSize(file.getSize());
        msg.setFileType(fileType);
        chatRepo.save(msg);

        ChatDto.MessageResponse response = toMessageResponse(msg, sender.getId());

        // Notify via WebSocket so the other party sees the file immediately
        messagingTemplate.convertAndSend(
                "/topic/chat/" + applicationId,
                Map.of("type", "NEW_MESSAGE", "message", response)
        );

        return response;
    }

    // ── Get messages (paginated) ─────────────────────────────────
    // OPTIMIZED: Replaces the old "load all messages at once" pattern.
    // - Page 0 loads the most-recent DEFAULT_PAGE_SIZE messages.
    // - Subsequent pages load older messages (infinite scroll upward).
    // - Uses a JOIN FETCH to load sender + reactions in one query (no N+1).
    // - Marks delivered + read inside the same transaction.

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ChatDto.PagedMessageResponse getMessages(Long applicationId, int page, String requesterEmail) {
        User requester = userRepo.findByEmail(requesterEmail).orElseThrow();
        Application app = appRepo.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found"));
        validateAccess(app, requester);

        // Mark delivered & read in one UPDATE (no SELECT loop)
        chatRepo.markAllDeliveredForApplication(app, requester);
        chatRepo.markAllReadForApplication(app, requester);

        // OPTIMIZED: Two-phase fetch to avoid Hibernate LIMIT + JOIN FETCH warning.
        // Phase 1 — get page of message IDs that are NOT deleted-for-me by this
        // viewer (excluded directly in SQL via NOT EXISTS on message_deletions,
        // using idx_msg_deletion_message_user). This means deleted messages
        // never leave the database for this user — no wasted bandwidth, and
        // pagination counts (totalElements/totalPages) are correct per-viewer.
        Page<Long> idPage = chatRepo.findVisibleIdsByApplicationPaged(
                app,
                requester,
                PageRequest.of(page, DEFAULT_PAGE_SIZE, Sort.by("sentAt").ascending())
        );

        // Phase 2 — fetch full messages with reactions JOIN in one query
        List<ChatMessage> messages = idPage.isEmpty()
                ? Collections.emptyList()
                : chatRepo.findByIdsWithReactions(idPage.getContent());

        // Sort again since IN query doesn't guarantee order
        messages.sort(Comparator.comparing(ChatMessage::getSentAt));

        List<ChatDto.MessageResponse> dtos = messages.stream()
                .map(m -> toMessageResponse(m, requester.getId()))
                .collect(Collectors.toList());

        ChatDto.PagedMessageResponse result = new ChatDto.PagedMessageResponse();
        result.setMessages(dtos);
        result.setPage(page);
        result.setSize(DEFAULT_PAGE_SIZE);
        result.setTotalElements(idPage.getTotalElements());
        result.setTotalPages(idPage.getTotalPages());
        result.setHasMore(page + 1 < idPage.getTotalPages());
        return result;
    }

    // ── Delete for me ────────────────────────────────────────────
    // WhatsApp-style: removes the message from the requester's own view
    // only. The other participant's view is completely unaffected — we
    // never touch ChatMessage.content here, only add a row to
    // message_deletions for (message, requester).

    @Transactional
    public ChatDto.DeleteForMeResponse deleteForMe(Long messageId, String requesterEmail) {
        User requester = userRepo.findByEmail(requesterEmail).orElseThrow();
        ChatMessage msg = chatRepo.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found"));

        // Authorization: only a participant of this conversation (the
        // candidate or the hiring manager on this application) may delete
        // their own copy. This also implicitly prevents one participant
        // from deleting-for-me a message in a conversation they're not part of.
        validateAccess(msg.getApplication(), requester);

        // Idempotent: re-deleting an already-deleted message for this user
        // is a harmless no-op rather than a unique-constraint violation.
        if (!deletionRepo.existsByMessageAndUser(msg, requester)) {
            MessageDeletion deletion = new MessageDeletion(msg, requester);
            deletionRepo.save(deletion);
        }

        // INTENTIONALLY NOT BROADCAST OVER WEBSOCKET:
        // Delete-for-me must remain invisible to the other participant.
        // Broadcasting on /topic/chat/{applicationId} would leak to both
        // sides since that topic is shared by the whole conversation.
        // If multi-device sync for the SAME user is needed later, push to
        // a per-user queue (e.g. /user/{email}/queue/chat-updates) instead.

        return new ChatDto.DeleteForMeResponse(msg.getId(), LocalDateTime.now().toString());
    }

    // ── Delete for everyone ──────────────────────────────────────
    // WhatsApp-style: only the original sender, only within a configurable
    // time window, replaces content with a tombstone for ALL participants,
    // and notifies everyone currently connected via WebSocket/STOMP.

    @Transactional
    public ChatDto.MessageResponse deleteForEveryone(Long messageId, String requesterEmail) {
        User requester = userRepo.findByEmail(requesterEmail).orElseThrow();

        // Lock-free read is fine here: the only writers of this row are
        // (a) this same method, guarded by the deletedForEveryone check
        //     below, which is naturally idempotent, and
        // (b) sendMessage/sendFile, which only ever INSERT new rows, never
        //     update an existing message's content.
        // So there's no concurrent-update race that needs a SELECT ... FOR UPDATE.
        ChatMessage msg = chatRepo.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found"));

        validateAccess(msg.getApplication(), requester);

        if (!msg.getSender().getId().equals(requester.getId())) {
            throw new ChatAccessDeniedException("Only the sender can delete this message for everyone");
        }

        // Already deleted — treat as idempotent rather than erroring, so a
        // double-click or retry on a flaky connection doesn't surface an
        // error to the user for an action that already succeeded.
        if (msg.isDeletedForEveryone()) {
            return toMessageResponse(msg, requester.getId());
        }

        // Configurable time window enforcement (default 60 minutes, see
        // chat.delete-for-everyone.window-minutes in application.properties).
        Duration elapsed = Duration.between(msg.getSentAt(), LocalDateTime.now());
        if (elapsed.toMinutes() >= deleteForEveryoneWindowMinutes) {
            throw new DeleteWindowExpiredException(
                    "Delete for everyone is only allowed within " + deleteForEveryoneWindowMinutes +
                            " minutes of sending this message");
        }

        LocalDateTime now = LocalDateTime.now();
        msg.setDeletedForEveryone(true);
        msg.setDeletedBy(requester);
        msg.setDeletedAt(now);

        // Content/file fields are cleared per requirements ("replace the
        // original message content with 'This message was deleted'"). The
        // audit trail that's preserved is the ChatMessage ROW ITSELF
        // (id, application, sender, sentAt, deletedBy, deletedAt) — only the
        // payload is wiped, not the fact that a message existed at that
        // point in the conversation.
        msg.setContent(null);
        msg.setFileUrl(null);
        msg.setFileName(null);
        msg.setFileType(null);
        msg.setFileSize(null);
        chatRepo.save(msg);

        ChatDto.MessageResponse response = toMessageResponse(msg, requester.getId());

        // Notify all connected participants so open chat windows update
        // immediately without needing to re-fetch the conversation.
        ChatDto.MessageDeletedEvent event = new ChatDto.MessageDeletedEvent(
                msg.getId(),
                msg.getApplication().getId(),
                requester.getId(),
                now.toString()
        );
        messagingTemplate.convertAndSend("/topic/chat/" + msg.getApplication().getId(), event);

        return response;
    }

    // ── Reactions ────────────────────────────────────────────────

    @Transactional
    public ChatDto.MessageResponse reactToMessage(Long messageId, String emoji, String requesterEmail) {
        User requester = userRepo.findByEmail(requesterEmail).orElseThrow();
        ChatMessage msg = chatRepo.findById(messageId)
                .orElseThrow(() -> new RuntimeException("Message not found"));
        validateAccess(msg.getApplication(), requester);

        Optional<MessageReaction> existing = reactionRepo.findByMessageAndUser(msg, requester);
        if (existing.isPresent()) {
            if (existing.get().getEmoji().equals(emoji)) {
                reactionRepo.delete(existing.get());
            } else {
                existing.get().setEmoji(emoji);
                reactionRepo.save(existing.get());
            }
        } else {
            MessageReaction reaction = new MessageReaction();
            reaction.setMessage(msg);
            reaction.setUser(requester);
            reaction.setEmoji(emoji);
            reactionRepo.save(reaction);
        }

        // OPTIMIZED: Re-fetch with reactions JOIN to avoid lazy load issues
        List<ChatMessage> updated = chatRepo.findByIdsWithReactions(List.of(messageId));
        ChatMessage updatedMsg = updated.isEmpty() ? msg : updated.get(0);
        ChatDto.MessageResponse response = toMessageResponse(updatedMsg, requester.getId());

        // Push reaction update via WebSocket
        messagingTemplate.convertAndSend(
                "/topic/chat/" + msg.getApplication().getId(),
                Map.of("type", "REACTION_UPDATED", "message", response)
        );

        return response;
    }

    // ── Manager conversations ────────────────────────────────────
    // OPTIMIZED: Replaces the N+1 loop that called findByApplicationOrderBySentAtAsc
    // for every conversation. Now uses two batched queries:
    //   1. findShortlistedApplicationsByManager — one query, JOIN FETCH
    //   2. findLastMessagesForApplications      — one query for ALL last messages
    //   3. countUnreadMessagesForApplications   — one aggregate query for ALL unread counts

    @Transactional
    public List<ChatDto.ConversationSummary> getConversationsForManager(String managerEmail) {
        User manager = userRepo.findByEmail(managerEmail).orElseThrow();
        List<Application> apps = appRepo.findShortlistedApplicationsByManager(manager, ApplicationStatus.SHORTLISTED);

        if (apps.isEmpty()) return Collections.emptyList();

        // Batch-load last messages for all applications (1 query instead of N)
        List<ChatMessage> lastMessages = chatRepo.findLastMessagesForApplications(apps);
        Map<Long, ChatMessage> lastMsgByAppId = lastMessages.stream()
                .collect(Collectors.toMap(m -> m.getApplication().getId(), m -> m, (a, b) -> b));

        // Batch-load unread counts for all applications (1 query instead of N)
        List<Object[]> unreadRaw = chatRepo.countUnreadMessagesForApplications(apps, manager);
        Map<Long, Long> unreadByAppId = unreadRaw.stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));

        return apps.stream().map(app -> {
            ChatDto.ConversationSummary summary = new ChatDto.ConversationSummary();
            summary.setApplicationId(app.getId());
            summary.setCandidateName(app.getApplicant().getName());
            summary.setCandidateEmail(app.getApplicant().getEmail());
            summary.setJobTitle(app.getJob().getTitle());
            summary.setApplicationStatus(app.getStatus().name());
            summary.setUnreadCount(unreadByAppId.getOrDefault(app.getId(), 0L));

            ChatMessage last = lastMsgByAppId.get(app.getId());
            if (last != null) {
                if (last.isDeletedForEveryone()) {
                    summary.setLastMessage(DELETED_MESSAGE_PLACEHOLDER);
                } else if (last.getFileUrl() != null) {
                    summary.setLastMessage("📎 " + last.getFileName());
                } else {
                    summary.setLastMessage(last.getContent());
                }
                summary.setLastMessageAt(last.getSentAt().toString());
            }
            return summary;
        }).collect(Collectors.toList());
    }

    public long getUnreadCountForJobseeker(String email) {
        User user = userRepo.findByEmail(email).orElseThrow();
        return chatRepo.countUnreadForJobseeker(user);
    }

    public long getUnreadCountForManager(String email) {
        User manager = userRepo.findByEmail(email).orElseThrow();
        return chatRepo.countConversationsWithUnreadForManager(manager);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void validateAccess(Application app, User user) {
        boolean isCandidate = app.getApplicant().getId().equals(user.getId());
        boolean isManager   = app.getJob().getCompany().getManager() != null &&
                app.getJob().getCompany().getManager().getId().equals(user.getId());
        if (!isCandidate && !isManager) {
            throw new ChatAccessDeniedException("Access denied: you are not a participant in this conversation");
        }
    }

    private void checkCandidateEligibility(Application app, User sender) {
        if (sender.getRole() == Role.JOBSEEKER &&
                app.getStatus() != ApplicationStatus.SHORTLISTED &&
                app.getStatus() != ApplicationStatus.HIRED) {
            throw new RuntimeException("Only shortlisted or hired candidates can interact with the recruiter");
        }
    }

    private ChatDto.MessageResponse toMessageResponse(ChatMessage msg, Long viewerUserId) {
        ChatDto.MessageResponse r = new ChatDto.MessageResponse();
        r.setId(msg.getId());
        r.setApplicationId(msg.getApplication().getId());
        r.setSenderId(msg.getSender().getId());
        r.setSenderName(msg.getSender().getName());
        r.setSenderRole(msg.getSender().getRole().name());
        r.setSentAt(msg.getSentAt().toString());
        r.setDelivered(msg.isDelivered());
        r.setRead(msg.isRead());
        r.setDeletedForEveryone(msg.isDeletedForEveryone());

        if (msg.isRead())           r.setStatus("READ");
        else if (msg.isDelivered()) r.setStatus("DELIVERED");
        else                        r.setStatus("SENT");

        if (msg.isDeletedForEveryone()) {
            // Requirement: "Replace the original message content with
            // 'This message was deleted.'" — every client (sender,
            // recipient, anyone re-fetching message history later) sees
            // the same tombstone text rather than null/blank.
            r.setContent(DELETED_MESSAGE_PLACEHOLDER);
            r.setFileUrl(null);
            r.setFileName(null);
            r.setFileSize(null);
            r.setFileType(null);
            r.setDeletedByUserId(msg.getDeletedBy() != null ? msg.getDeletedBy().getId() : null);
            r.setDeletedAt(msg.getDeletedAt() != null ? msg.getDeletedAt().toString() : null);
        } else {
            r.setContent(msg.getContent());
            r.setFileUrl(msg.getFileUrl());
            r.setFileName(msg.getFileName());
            r.setFileSize(msg.getFileSize());
            r.setFileType(msg.getFileType());
        }

        // OPTIMIZED: Reactions are now LAZY, loaded via JOIN FETCH in the query layer.
        // getReactions() is safe here because we always call findByIdsWithReactions()
        // (which JOIN FETCHes reactions) before calling toMessageResponse().
        Map<String, ChatDto.ReactionSummary> grouped = new LinkedHashMap<>();
        List<MessageReaction> reactionList = msg.getReactions();
        if (reactionList != null) {
            for (MessageReaction reaction : reactionList) {
                String emoji = reaction.getEmoji();
                ChatDto.ReactionSummary rs = grouped.computeIfAbsent(emoji, e -> {
                    ChatDto.ReactionSummary s = new ChatDto.ReactionSummary();
                    s.setEmoji(e);
                    s.setCount(0);
                    return s;
                });
                rs.setCount(rs.getCount() + 1);
                if (reaction.getUser().getId().equals(viewerUserId)) {
                    rs.setReactedByMe(true);
                }
            }
        }
        r.setReactions(new ArrayList<>(grouped.values()));
        return r;
    }
}