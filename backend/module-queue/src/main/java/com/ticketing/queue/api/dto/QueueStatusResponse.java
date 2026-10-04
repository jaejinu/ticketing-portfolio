package com.ticketing.queue.api.dto;

import com.ticketing.queue.application.QueueStatusSnapshot;

import java.util.UUID;

public record QueueStatusResponse(
        String state,
        UUID ticket,
        UUID scheduleId,
        long position,
        long estimatedWaitSeconds
) {

    public static QueueStatusResponse of(QueueStatusSnapshot snap) {
        return new QueueStatusResponse(
                snap.state(), snap.ticket(), snap.scheduleId(),
                snap.position(), snap.estimatedWaitSeconds());
    }
}
