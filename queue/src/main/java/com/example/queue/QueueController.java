package com.example.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@Slf4j
@RestController
@RequiredArgsConstructor
public class QueueController {

    private final SseEmitterService sseEmitterService;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<?>> queue() {
        log.info("sse 시작");

        return sseEmitterService.getStream()
                .map(message -> ServerSentEvent.<String>builder()
                        .event("queue") // 클라이언트에서 "queue" 이벤트 구
                        .data(message) // 문자열 메세지 데이터 포함
                        .build());
    }
}
