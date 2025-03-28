package com.example.queue;

public record QueueDTO(
        String messageId,
        String recordIdValue,
        int pendingSize,
        long queueSize
) {
}
