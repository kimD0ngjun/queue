package com.example.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PendingMessageScheduler {

    private static final String STREAM_KEY = "queue";
    private static final String CONSUMER_GROUP = "consumers";
    private static final String CONSUMER_NAME = "netty";

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    @Scheduled(fixedRate = 10000)
    public void processPendingMessages() {
}
