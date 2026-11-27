package com.psar.notification.controller;

import com.psar.notification.entity.Notification;
import com.psar.notification.entity.NotificationType;
import com.psar.notification.service.NotificationService;
import com.psar.support.IntegrationTest;
import com.psar.support.SystemFixtures;
import com.psar.support.TestAuth;
import com.psar.user.entity.Role;
import com.psar.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationControllerTest extends IntegrationTest {

    private static final String URL = "/api/notifications";

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private SystemFixtures system;

    private User me;
    private User other;

    @BeforeEach
    void setUp() {
        me = system.user(Role.CUSTOMER);
        other = system.user(Role.MANAGER);
    }

    @Test
    void list_mine_newestFirstWithoutUserId() throws Exception {
        notify(me, "First");
        notify(me, "Second");
        notify(other, "Not mine");

        mockMvc.perform(get(URL).with(TestAuth.as(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].title").value("Second"))
                .andExpect(jsonPath("$.content[1].title").value("First"))
                .andExpect(jsonPath("$.content[0].type").value("LOW_STOCK"))
                .andExpect(jsonPath("$.content[0].read").value(false))
                .andExpect(jsonPath("$.content[0].publicId").exists())
                .andExpect(jsonPath("$.content[0].createdAt").exists())
                .andExpect(jsonPath("$.content[0]", not(hasKey("userId"))))
                .andExpect(jsonPath("$.content[0]", not(hasKey("id"))));
    }

    @Test
    void list_unreadOnly_skipsReadOnes() throws Exception {
        Notification read = notify(me, "Read");
        notify(me, "Unread");
        notificationService.markRead(me.getId(), read.getPublicId());

        mockMvc.perform(get(URL).param("unread", "true").with(TestAuth.as(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Unread"));
    }

    @Test
    void list_pageSizeOver100_cappedAt100() throws Exception {
        mockMvc.perform(get(URL).param("size", "500").param("sort", "userId,asc").with(TestAuth.as(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void unreadCount_afterMarkRead_decreases() throws Exception {
        Notification first = notify(me, "One");
        notify(me, "Two");

        mockMvc.perform(get(URL + "/unread-count").with(TestAuth.as(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));

        mockMvc.perform(patch(URL + "/{id}/read", first.getPublicId()).with(TestAuth.as(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(first.getPublicId().toString()))
                .andExpect(jsonPath("$.read").value(true));

        mockMvc.perform(get(URL + "/unread-count").with(TestAuth.as(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void markRead_alreadyRead_keepsFirstReadTime() throws Exception {
        Notification n = notify(me, "Twice");
        mockMvc.perform(patch(URL + "/{id}/read", n.getPublicId()).with(TestAuth.as(me)))
                .andExpect(status().isOk());
        var firstReadAt = reload(me, n).getReadAt();

        mockMvc.perform(patch(URL + "/{id}/read", n.getPublicId()).with(TestAuth.as(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));

        assertThat(reload(me, n).getReadAt()).isEqualTo(firstReadAt);
    }

    @Test
    void markRead_otherUsersNotification_returns404() throws Exception {
        Notification theirs = notify(other, "Theirs");

        mockMvc.perform(patch(URL + "/{id}/read", theirs.getPublicId()).with(TestAuth.as(me)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(reload(other, theirs).isRead()).isFalse();
    }

    @Test
    void markRead_unknownId_returns404() throws Exception {
        mockMvc.perform(patch(URL + "/{id}/read", UUID.randomUUID()).with(TestAuth.as(me)))
                .andExpect(status().isNotFound());
    }

    @Test
    void readAll_marksOnlyMine_returnsCount() throws Exception {
        notify(me, "A");
        notify(me, "B");
        Notification theirs = notify(other, "C");

        mockMvc.perform(post(URL + "/read-all").with(TestAuth.as(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(2));

        assertThat(system.notifications(me, NotificationType.LOW_STOCK)).allMatch(Notification::isRead);
        assertThat(reload(other, theirs).isRead()).isFalse();

        mockMvc.perform(post(URL + "/read-all").with(TestAuth.as(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(0));
    }

    @Test
    void notifications_noLogin_returns401() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/unread-count")).andExpect(status().isUnauthorized());
        mockMvc.perform(patch(URL + "/{id}/read", UUID.randomUUID())).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/read-all")).andExpect(status().isUnauthorized());
    }

    private Notification notify(User user, String title) {
        notificationService.notifyUsers(List.of(user.getId()), NotificationType.LOW_STOCK, title,
                title + " message", "/admin/inventory/low-stock");
        return system.notifications(user, NotificationType.LOW_STOCK).getLast();
    }

    private Notification reload(User user, Notification notification) {
        return system.notifications(user, NotificationType.LOW_STOCK).stream()
                .filter(n -> n.getPublicId().equals(notification.getPublicId()))
                .findFirst().orElseThrow();
    }
}
