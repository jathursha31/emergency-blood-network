package com.blooddonor.service;

import com.blooddonor.dto.ChatDtos.Conversation;
import com.blooddonor.dto.ChatDtos.MessageView;
import com.blooddonor.dto.ChatDtos.Unread;
import com.blooddonor.entity.*;
import com.blooddonor.entity.ChatMessage.Sender;
import com.blooddonor.exception.BusinessRuleException;
import com.blooddonor.exception.ForbiddenException;
import com.blooddonor.exception.NotFoundException;
import com.blooddonor.exception.TooManyRequestsException;
import com.blooddonor.live.LiveEvent;
import com.blooddonor.push.PushMessage;
import com.blooddonor.repository.ChatMessageRepository;
import com.blooddonor.repository.DonorResponseRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chat between a hospital and a donor about one blood request (directions, timing, "I'm at the gate").
 * <ul>
 *   <li>Opens when the donor accepts; donors who were only notified cannot chat.</li>
 *   <li>Stays open while the request is active and for {@value #HOURS_AFTER_CLOSE} hours after it closes
 *       (for "thank you" and follow-ups), then becomes read-only.</li>
 *   <li>Opening a conversation marks the other side's messages as read.</li>
 *   <li>Each side can send at most {@value #MAX_PER_MINUTE} messages a minute.</li>
 *   <li>Live events carry only ids; the text is fetched over the authenticated API.</li>
 * </ul>
 */
@Service
@Transactional
public class ChatService {

    static final int HOURS_AFTER_CLOSE = 24;
    static final int MAX_PER_MINUTE = 20;
    private static final Set<ResponseStatus> CHAT_STATUSES = EnumSet.of(ResponseStatus.ACCEPTED, ResponseStatus.DONATED);
    private static final Set<RequestStatus> ACTIVE = EnumSet.of(RequestStatus.OPEN, RequestStatus.MATCHED);

    private final ChatMessageRepository messages;
    private final DonorResponseRepository responses;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ChatService(ChatMessageRepository messages, DonorResponseRepository responses,
                       ApplicationEventPublisher events, Clock clock) {
        this.messages = messages;
        this.responses = responses;
        this.events = events;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reading

    public Conversation forDonor(Long requestId, Long donorId) {
        DonorResponse response = response(requestId, donorId);
        List<ChatMessage> list = messages.findByResponseIdOrderByCreatedAtAscIdAsc(response.getId());
        if (!CHAT_STATUSES.contains(response.getStatus()) && list.isEmpty()) {
            throw new ForbiddenException("Accept the request to chat with the hospital");
        }
        messages.markRead(response.getId(), Sender.HOSPITAL, now());
        return toConversation(response, list, Sender.DONOR);
    }

    /**
     * Caller has already checked that the user is this request's hospital (or an admin).
     * markRead false for admins looking in, so the hospital's own unread count stays.
     */
    public Conversation forHospital(Long requestId, Long donorId, boolean markRead) {
        DonorResponse response = response(requestId, donorId);
        List<ChatMessage> list = messages.findByResponseIdOrderByCreatedAtAscIdAsc(response.getId());
        if (markRead) messages.markRead(response.getId(), Sender.DONOR, now());
        return toConversation(response, list, Sender.HOSPITAL);
    }

    @Transactional(readOnly = true)
    public List<Unread> unreadForDonor(Long donorId) {
        return messages.unreadForDonor(donorId).stream()
                .map(r -> new Unread((Long) r[0], donorId, (Long) r[1])).toList();
    }

    /** hospitalId null = every hospital (admin). */
    @Transactional(readOnly = true)
    public List<Unread> unreadForHospital(Long hospitalId) {
        return messages.unreadForHospital(hospitalId).stream()
                .map(r -> new Unread((Long) r[0], (Long) r[1], (Long) r[2])).toList();
    }

    // ---------------------------------------------------------------- sending

    /** byAdmin: an admin writing on the hospital's behalf (shown as "<hospital> (admin)"). */
    public MessageView send(Long requestId, Long donorId, Sender sender, boolean byAdmin, String rawBody) {
        DonorResponse response = response(requestId, donorId);
        String senderName = sender == Sender.DONOR ? response.getDonor().getFullName()
                : response.getRequest().getHospital().getName() + (byAdmin ? " (admin)" : "");
        String closed = closedReason(response);
        if (closed != null) throw new BusinessRuleException(closed);
        String body = rawBody == null ? "" : rawBody.strip();
        if (body.isEmpty()) throw new BusinessRuleException("Message is empty");
        if (body.length() > 1000) throw new BusinessRuleException("Message is too long (max 1000 characters)");
        if (messages.countByResponseIdAndSenderAndCreatedAtAfter(response.getId(), sender, now().minusMinutes(1)) >= MAX_PER_MINUTE) {
            throw new TooManyRequestsException("Too many messages, wait a moment", 60);
        }

        ChatMessage m = new ChatMessage();
        m.setResponse(response);
        m.setSender(sender);
        m.setSenderName(senderName.length() > 120 ? senderName.substring(0, 120) : senderName);
        m.setBody(body);
        m.setCreatedAt(now());
        messages.save(m);

        // replying means the sender has seen what the other side wrote
        if (!byAdmin) messages.markRead(response.getId(), sender == Sender.DONOR ? Sender.HOSPITAL : Sender.DONOR, now());
        BloodRequest request = response.getRequest();
        Long donor = response.getDonor().getId();
        Map<String, Object> data = Map.of("requestId", request.getId(), "donorId", donor, "from", sender.name());
        events.publishEvent(new LiveEvent("chat.message", Set.of(request.getHospital().getId()), Set.of(donor), data));
        if (sender == Sender.HOSPITAL) {
            events.publishEvent(new PushMessage(Set.of(donor), "💬 " + request.getHospital().getName(),
                    body.length() > 140 ? body.substring(0, 137) + "…" : body,
                    "/donor?chat=" + request.getId(), "chat-" + request.getId(), false));
        }
        return toView(m, sender);
    }

    // ---------------------------------------------------------------- helpers

    private DonorResponse response(Long requestId, Long donorId) {
        return responses.findByRequestIdAndDonorId(requestId, donorId)
                .orElseThrow(() -> new NotFoundException("Donor response", requestId + "/" + donorId));
    }

    /** null when messages can be sent, otherwise the reason they can't. */
    private String closedReason(DonorResponse response) {
        if (!CHAT_STATUSES.contains(response.getStatus())) {
            return response.getStatus() == ResponseStatus.DECLINED
                    ? "The donor is no longer coming for this request"
                    : "Chat opens once the donor accepts the request";
        }
        BloodRequest request = response.getRequest();
        if (ACTIVE.contains(request.getStatus())) return null;
        LocalDateTime closedAt = request.getClosedAt();
        if (closedAt != null && Duration.between(closedAt, now()).toHours() < HOURS_AFTER_CLOSE) return null;
        return "This request is closed, so the chat is read-only";
    }

    private Conversation toConversation(DonorResponse r, List<ChatMessage> list, Sender viewer) {
        String closed = closedReason(r);
        return new Conversation(r.getRequest().getId(), r.getDonor().getId(), r.getRequest().getHospital().getName(),
                r.getDonor().getFullName(), closed == null, closed, list.stream().map(m -> toView(m, viewer)).toList());
    }

    private static MessageView toView(ChatMessage m, Sender viewer) {
        return new MessageView(m.getId(), m.getSender(), m.getSenderName(), m.getBody(), m.getCreatedAt(), m.getReadAt(),
                m.getSender() == viewer);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
