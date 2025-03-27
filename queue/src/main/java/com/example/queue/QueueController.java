package com.example.queue;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.io.IOException;

@Slf4j
@RestController
public class QueueController {

    @GetMapping(value = "/news", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<?>> queue() throws IOException {
        log.info("sse 시작");

//        return Flux.fromIterable()
//                .window(3)  // 데이터를 3개씩 분할
//                .delayElements(Duration.ofSeconds(1))  // 각 윈도우마다 1초 대기
//                .flatMap(Flux::collectList)  // 각 윈도우를 List로 변환
//                .map(articles -> ServerSentEvent.<List<Article>>builder()
//                        .event("news")  // 이벤트 타입 설정
//                        .data(articles)  // 데이터 설정
//                        .build());
        return null;
    }
}
