package com.blooddonor.dto;

import com.blooddonor.entity.ChatMessage.Sender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class ChatDtos {

    private ChatDtos() {
    }

    public record SendMessage(@NotBlank @Size(max = 1000) String body) {
    }

    /** mine = sent by the side that is asking. readAt = when the other side saw it. */
    public record MessageView(Long id, Sender sender, String senderName, String body,
                              LocalDateTime createdAt, LocalDateTime readAt, boolean mine) {
    }

    /**
     * @param canSend     false once the conversation is closed
     * @param closedReason why sending is closed (shown instead of the input box), null when open
     */
    public record Conversation(Long requestId, Long donorId, String hospitalName, String donorName,
                               boolean canSend, String closedReason, List<MessageView> messages) {
    }

    public record Unread(Long requestId, Long donorId, long count) {
    }
}
