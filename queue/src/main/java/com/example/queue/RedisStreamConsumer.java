package com.example.queue;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisStreamConsumer
        implements StreamListener<String, MapRecord<String, Object, Object>>, InitializingBean, DisposableBean {

    private static final String STREAM_KEY = "queue";
    private static final String CONSUMER_GROUP = "consumers";
    private static final String CONSUMER_NAME = "netty";

    private Subscription subscription;

    private final StreamMessageListenerContainer<String, MapRecord<String, Object, Object>> listenerContainer;
    private final RedisTemplate<String, String> redisTemplate;
    private final SseEmitterService sseEmitterService;

    /**
     * 메세지를 onMessage 메소드로 받아오는 시점부터 XREADGROUP -> 이때부터 Pending 상태 -> XACK 처리를 해줘야 대기열에서 벗어남(중복 작업 x)
     * @param message
     */
    @Override
    public void onMessage(MapRecord<String, Object, Object> message) {
        // SSE를 통해 클라이언트에게 각 사용자별 진행률 계산용 전달
        sseEmitterService.sendMessage(message);

        // 수신 메세지 Ack 처리
        redisTemplate.opsForStream().acknowledge(CONSUMER_GROUP, message);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        // 큐 있는지 확인하고 없으면 큐 생성
        if (Boolean.FALSE.equals(redisTemplate.hasKey(STREAM_KEY))) {
            log.info("스트림 'queue'가 존재하지 않음. 스트림 생성 및 첫 번째 메시지 추가.");
            // 스트림에 첫 번째 메시지 추가
            redisTemplate.opsForStream().add(STREAM_KEY, Map.of("userId", "systemInit"));
        } else {
            log.info("스트림 'queue'가 이미 존재합니다.");
        }

        // 컨슈머 그룹 세팅
        if (!isStreamConsumerGroupExist()) {
            log.info("컨슈머 그룹 생성");
            redisTemplate.opsForStream()
                    .createGroup(STREAM_KEY, ReadOffset.from("0"), CONSUMER_GROUP);
        }

        // 중재자 세팅(단일 컨슈머 그룹에서 메세지를 레디스 스트림 순서대로 수신)
        this.subscription = listenerContainer.receive(
                Consumer.from(CONSUMER_GROUP, CONSUMER_NAME),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()),
                this
        );

        // 5초 간격으로 정보 취득(블로킹 처리)
        this.subscription.await(Duration.ofSeconds(5));

        // Redis Listen 시작
        this.listenerContainer.start();
    }

    @PreDestroy
    @Override
    public void destroy() {
        try {
            if (this.listenerContainer != null) {
                log.info("리스너 컨테이너 종료");
                this.listenerContainer.stop();
            }

            if (this.subscription != null) {
                log.info("중재자 종료");
                this.subscription.cancel();
            }
        } catch (Exception e) {
            log.error("Redis Stream Listener Container 종료 중 오류 발생: {}", e.getMessage());
        }
    }

    private boolean isStreamConsumerGroupExist() {
        // 소비자 그룹이 존재하는지 확인하는 로직
        return redisTemplate.opsForStream().groups(STREAM_KEY)
                .stream()
                .anyMatch(group -> group.groupName().equals(CONSUMER_GROUP));
    }
}
