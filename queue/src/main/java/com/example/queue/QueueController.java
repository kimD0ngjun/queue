package com.example.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequiredArgsConstructor
public class QueueController {

    private final SseEmitterService sseEmitterService;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter queue(
            @RequestParam("userId") String userId,
            @RequestParam("messageId") String messageId
    ) {
        log.info("sse 시작: {} / {}", userId, messageId);
        return sseEmitterService.createEmitter(userId, messageId);
    }
}
