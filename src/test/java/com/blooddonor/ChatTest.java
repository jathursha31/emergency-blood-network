package com.blooddonor;

import com.blooddonor.dto.ChatDtos.Conversation;
import com.blooddonor.dto.ChatDtos.Unread;
import com.blooddonor.dto.DonorDtos.DonorView;
import com.blooddonor.dto.RequestDtos.BloodRequestView;
import com.blooddonor.entity.BloodGroup;
import com.blooddonor.entity.ChatMessage.Sender;
import com.blooddonor.exception.BusinessRuleException;
import com.blooddonor.exception.ForbiddenException;
import com.blooddonor.exception.TooManyRequestsException;
import com.blooddonor.live.LiveEvent;
import com.blooddonor.push.PushMessage;
import com.blooddonor.service.BloodRequestService;
import com.blooddonor.service.ChatService;
import com.blooddonor.service.DonorService;
import com.blooddonor.service.HospitalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Hospital-donor chat: who may talk, unread counts, limits and notifications. */
@SpringBootTest
@RecordApplicationEvents
class ChatTest {

    @Autowired HospitalService hospitalService;
    @Autowired DonorService donorService;
    @Autowired BloodRequestService requestService;
    @Autowired ChatService chat;
    @Autowired ApplicationEvents events;

    Fixtures fx;
    Long hospitalId;
    DonorView donor;
    BloodRequestView request;

    @BeforeEach
    void setUp() {
        fx = new Fixtures(hospitalService, donorService, requestService);
        hospitalId = fx.hospital("Chat Hospital");
        // a rare group so only this test's donor is matched
        donor = fx.donor("Chat Donor", BloodGroup.AB_NEG);
        request = fx.request(hospitalId, BloodGroup.AB_NEG, 1);
    }

    @Test
    void chatOpensOnlyAfterTheDonorAccepts() {
        assertThatThrownBy(() -> chat.forDonor(request.id(), donor.id())).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> chat.send(request.id(), donor.id(), Sender.HOSPITAL, false, "Hello"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("accepts");

        requestService.accept(request.id(), donor.id());
        Conversation c = chat.forDonor(request.id(), donor.id());
        assertThat(c.canSend()).isTrue();
        assertThat(c.hospitalName()).isEqualTo("Chat Hospital");
        assertThat(c.donorName()).isEqualTo("Chat Donor");
        assertThat(c.messages()).isEmpty();
    }

    @Test
    void conversationWithUnreadCountsLiveEventsAndPush() {
        requestService.accept(request.id(), donor.id());
        events.clear();

        chat.send(request.id(), donor.id(), Sender.HOSPITAL, false, "  Please come to Gate 2, blood bank on 1st floor.  ");
        assertThat(chat.unreadForDonor(donor.id())).containsExactly(new Unread(request.id(), donor.id(), 1));

        LiveEvent live = events.stream(LiveEvent.class).filter(e -> e.type().equals("chat.message")).findFirst().orElseThrow();
        assertThat(live.hospitalIds()).containsExactly(hospitalId);
        assertThat(live.donorIds()).containsExactly(donor.id());
        assertThat(live.data()).doesNotContainKey("body");
        PushMessage push = events.stream(PushMessage.class).findFirst().orElseThrow();
        assertThat(push.donorIds()).containsExactly(donor.id());
        assertThat(push.title()).isEqualTo("💬 Chat Hospital");
        assertThat(push.body()).isEqualTo("Please come to Gate 2, blood bank on 1st floor.");
        assertThat(push.url()).isEqualTo("/donor?chat=" + request.id());

        // donor opens the chat: read, and replies
        Conversation forDonor = chat.forDonor(request.id(), donor.id());
        assertThat(forDonor.messages()).singleElement().satisfies(m -> {
            assertThat(m.mine()).isFalse();
            assertThat(m.senderName()).isEqualTo("Chat Hospital");
            assertThat(m.body()).isEqualTo("Please come to Gate 2, blood bank on 1st floor.");
        });
        assertThat(chat.unreadForDonor(donor.id())).isEmpty();

        events.clear();
        chat.send(request.id(), donor.id(), Sender.DONOR, false, "On my way, 10 minutes");
        assertThat(events.stream(PushMessage.class)).as("donor replies are not pushed").isEmpty();
        assertThat(chat.unreadForHospital(hospitalId)).containsExactly(new Unread(request.id(), donor.id(), 1));
        assertThat(chat.unreadForHospital(null)).contains(new Unread(request.id(), donor.id(), 1));

        // an admin looking in does not clear the hospital's unread count, and is named as admin
        chat.forHospital(request.id(), donor.id(), false);
        chat.send(request.id(), donor.id(), Sender.HOSPITAL, true, "Admin here, all good?");
        assertThat(chat.unreadForHospital(hospitalId)).hasSize(1);

        Conversation forHospital = chat.forHospital(request.id(), donor.id(), true);
        assertThat(chat.unreadForHospital(hospitalId)).isEmpty();
        assertThat(forHospital.messages()).extracting(m -> m.senderName() + ":" + m.mine())
                .containsExactly("Chat Hospital:true", "Chat Donor:false", "Chat Hospital (admin):true");
        // the donor has seen the first hospital message
        assertThat(forHospital.messages().get(0).readAt()).isNotNull();
    }

    @Test
    void rejectsEmptyTooLongAndFloods() {
        requestService.accept(request.id(), donor.id());
        assertThatThrownBy(() -> chat.send(request.id(), donor.id(), Sender.DONOR, false, "   "))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> chat.send(request.id(), donor.id(), Sender.DONOR, false, "x".repeat(1001)))
                .isInstanceOf(BusinessRuleException.class);

        for (int i = 0; i < 20; i++) chat.send(request.id(), donor.id(), Sender.DONOR, false, "msg " + i);
        assertThatThrownBy(() -> chat.send(request.id(), donor.id(), Sender.DONOR, false, "one more"))
                .isInstanceOf(TooManyRequestsException.class);
        // the limit is per side: the hospital can still answer
        chat.send(request.id(), donor.id(), Sender.HOSPITAL, false, "Got it");
    }

    @Test
    void donorWhoBacksOutKeepsHistoryButCannotSend() {
        requestService.accept(request.id(), donor.id());
        chat.send(request.id(), donor.id(), Sender.HOSPITAL, false, "See you at 5");
        requestService.decline(request.id(), donor.id());

        Conversation c = chat.forDonor(request.id(), donor.id());
        assertThat(c.canSend()).isFalse();
        assertThat(c.closedReason()).contains("no longer coming");
        assertThat(c.messages()).hasSize(1);
        assertThatThrownBy(() -> chat.send(request.id(), donor.id(), Sender.HOSPITAL, false, "Hello?"))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void chatStaysOpenForADayAfterDonation() {
        requestService.accept(request.id(), donor.id());
        requestService.markDonated(request.id(), donor.id());   // 1 unit needed: request is now FULFILLED
        Conversation c = chat.forHospital(request.id(), donor.id(), true);
        assertThat(c.canSend()).isTrue();
        chat.send(request.id(), donor.id(), Sender.HOSPITAL, false, "Thank you for donating!");
    }
}
