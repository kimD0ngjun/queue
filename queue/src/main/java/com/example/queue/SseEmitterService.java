package com.example.queue;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Service
public class SseEmitterService {

    // 비동기 데이터 스트림 생성(중앙 허브 역할)
    private final Sinks.Many<String> sink;

    public SseEmitterService() {
        // 다중 구독자가 동시에 데이터를 받을 수 있게
        this.sink = Sinks.many().multicast().onBackpressureBuffer();
    }

    // 새로운 메세지 Sink Push
    // 객체 파라미터일 경우여도 문자열 직렬화 과정 필요
    public void sendMessage(String message) {
        sink.tryEmitNext(message);
    }

    // SSE 엔드포인트에서 Flux 스트림 반환하여 클라이언트에게 푸시
    public Flux<String> getStream() {
        return sink.asFlux();
    }
}
