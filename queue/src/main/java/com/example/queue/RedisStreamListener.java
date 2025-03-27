package com.example.queue;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisStreamListener {

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    private static final String STREAM_KEY = "queue";
    private static final String CONSUMER_GROUP = "consumers";
    private static final String CONSUMER_NAME = "netty";

    @Scheduled(fixedRate = 5000)
    public void listenStream() {
        log.info("메세지 리스닝 스케줄링");

        // 스트림 메시지 읽기
        Flux<MapRecord<String, Object, Object>> flux = redisTemplate.opsForStream().read(
                Consumer.from(CONSUMER_GROUP, CONSUMER_NAME),
                StreamReadOptions.empty().block(Duration.ofSeconds(5)),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
        );

        flux.doOnNext(message -> {
            log.info("수신 아이디: {}", message.getId());
            log.info("수신 메세지: {}", message.getValue());

            // Ack 처리 (중복처리 방지)
            redisTemplate.opsForStream().acknowledge(CONSUMER_GROUP, message);
        }).doOnError(error -> {
            log.error("오류 발생: ", error);
        }).subscribe();
    }

    @PostConstruct
    private void createConsumerGroupIfNotExist() {
        if (!isStreamConsumerGroupExist()) {
            redisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0"), CONSUMER_GROUP);
        }
    }

    private boolean isStreamConsumerGroupExist() {
        // 소비자 그룹이 존재하는지 확인하는 로직
        return redisTemplate.opsForStream().groups(STREAM_KEY)
                .toStream()
                .anyMatch(group -> group.groupName().equals(CONSUMER_GROUP));
    }
}
