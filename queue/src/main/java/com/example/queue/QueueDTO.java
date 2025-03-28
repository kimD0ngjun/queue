package com.example.queue;

public record QueueDTO(
        String userId,
        String recordIdValue,
        int pendingSize,
        long queueSize
) {
}
