package com.example.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class SseEmitterService {

    private static final String STREAM_KEY = "queue";
    private static final String CONSUMER_GROUP = "consumers";
    private static final String CONSUMER_NAME = "netty";

    private Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private ObjectMapper objectMapper = new ObjectMapper();

    private final RedisTemplate<String, String> redisTemplate;

    // 사용자별 SSE 연결 생성
    public SseEmitter createEmitter(String userId) {
        SseEmitter emitter = new SseEmitter(60_000L); // 60초 타임아웃
        emitters.put(userId, emitter);

        log.info("emitter 생성 확인: {}", emitters.get(userId));

        // 클라이언트 연결 종료 시 자동 삭제
        emitter.onCompletion(() -> emitters.remove(userId));
        emitter.onTimeout(() -> emitters.remove(userId));

        log.info("SSE 연결 생성: userId={}", userId);
        return emitter;
    }

    // 특정 사용자에게 메시지 전송
    public void sendMessage(String userId) {
        int percent = getProgressPercentage(userId);
        log.info("현재 포지션: {}", percent);

        SseEmitter emitter = emitters.get(userId);
        QueueDTO dto = new QueueDTO(userId, percent);

        if (emitter != null) {
            try {
                String message = objectMapper.writeValueAsString(dto);
                emitter.send(SseEmitter.event().name("queue").data(message));
                log.info("SSE 메시지 전송: userId={}, message={}", userId, message);
            } catch (Exception e) {
                log.error("SSE 전송 실패: userId={}, error={}", userId, e.getMessage());
                emitters.remove(userId); // 전송 실패 시 제거
            }
        } else {
            log.warn("SSEEmitter 없음: userId={}", userId);
        }
    }

    private int getProgressPercentage(String userId) {
        PendingMessages pendingMessages = redisTemplate.opsForStream()
                .pending(STREAM_KEY, Consumer.from(CONSUMER_GROUP, CONSUMER_NAME), Range.unbounded(), 10000L);

        long pendingCount = pendingMessages.stream().count(); // 자신을 포함한 미처리 메세지 갯수 세림
        log.info("미처리 메시지 개수: {}", pendingCount);

        List<MapRecord<String, Object, Object>> queue
                = redisTemplate.opsForStream().range(STREAM_KEY, Range.unbounded(), Limit.limit().count((int) pendingCount));

        int position = 0;
        assert queue != null;
        for (MapRecord<String, Object, Object> record : queue) {
            String recordUserId = (String) record.getValue().get("userId");
            if (userId.equals(recordUserId)) {
                int progressPercentage;

                if (pendingCount == 0) {
                    progressPercentage = 100; // 대기열이 없으면 100% 완료
                } else {
                    progressPercentage = (int) ((1 - (position / (double) pendingCount)) * 100);
                }

                log.info("현재 순위: {}, 대기열 크기: {}, 진행률: {}%", position, pendingCount, progressPercentage);

                return progressPercentage;
            }
            position++;
        }

        return -1; // 유저가 대기열에 없으면 -1 반환
    }
}
