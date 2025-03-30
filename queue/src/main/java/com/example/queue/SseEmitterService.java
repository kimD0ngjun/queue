package com.example.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 분모는 전체 큐 사이즈 * N배, 분자는 자신의 아이디 - 현재 처리중인 메세지 아이디?
@Slf4j
@Service
@RequiredArgsConstructor
public class SseEmitterService {

    private static final String STREAM_KEY = "queue";
    private static final String CONSUMER_GROUP = "consumers";
    private static final String CONSUMER_NAME = "netty";

    private Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private Map<String, QueueDTO> records = new ConcurrentHashMap<>();
    private Map<String, String> messageIds = new ConcurrentHashMap<>();
    private String firstMessageId;
    private ObjectMapper objectMapper = new ObjectMapper();

    private final RedisTemplate<String, String> redisTemplate;

    // 사용자별 SSE 연결 생성
    public SseEmitter createEmitter(String userId, String messageId) {
        messageIds.put(userId, messageId); // Stream 메세지 ID 저장

        SseEmitter emitter = new SseEmitter(60_000L); // 60초 타임아웃
        emitters.put(userId, emitter); // SSE Emitter 저장

        // 클라이언트 연결 종료 시 자동 삭제
        emitter.onCompletion(() -> emitters.remove(userId));
        emitter.onTimeout(() -> emitters.remove(userId));

        // 이미 수신돼서 대기중인 메세지가 있으면(거의 100프로 있다 봐야 할듯...?)
        if (records.containsKey(userId)) {
            log.info("이미 대기중인 메세지 존재: {}", userId);

            try {
                String response = objectMapper.writeValueAsString(records.get(userId));
                emitter.send(SseEmitter.event().name("queue").data(response));
                log.info("SSE 메시지 전송: userId={}, message={}", userId, response);

                // SSE 연결 종료 책임은 클라이언트에게
            } catch (Exception e) {
                log.error("SSE 전송 실패: userId={}, error={}", userId, e.getMessage());
                emitter.onCompletion(() -> emitters.remove(userId)); // 전송 실패 시 제거
            }
        }

        return emitter;
    }

    public void sendMessage(MapRecord<String, Object, Object> message) {
        String messageId = message.getId().getValue();
        log.info("메세지 ID: {}", messageId);

        String userId = (String) message.getValue().get("userId");
        log.info("메세지 발신자 식별값: {}", userId);

        // 아직 emitter가 생성되지 않음(메세지가 emitter 생성보다 먼저 도착)
        int percent;
        if (!emitters.containsKey(userId)) {
            log.info("아직 {} SSE Emitter 생성 안됨", userId);

            // 아직 메세지 ID 도착 안 했다면?
            if (!messageIds.containsKey(userId)) {
                percent = 0;
            } else {
                String userMessageId = messageIds.get(userId);
                long currentTime = extractTimeStamp(messageId);
                long userTime = extractTimeStamp(userMessageId);

                percent = (int) ((int) 100.0 * Math.exp(-1 * (userTime - currentTime)));
            }

            log.info("emitter 생성 안된 시점에서 사용자 및 진행률: {}, {}", userId, percent);
            QueueDTO dto = new QueueDTO(userId, percent);
            records.put(userId, dto);
        }

        // emitter가 생성되어 있다면
        emitters.forEach((clientUserId, emitter) -> {
            log.info("emitter 존재: {}", clientUserId);
            int processPercent;

            if (!messageIds.containsKey(userId)) {
                processPercent = 0;
            } else {
                String clientMessageId = messageIds.get(clientUserId);
                long currentTime = extractTimeStamp(messageId);
                long userTime = extractTimeStamp(clientMessageId);

                processPercent = (int) ((int) 100.0 * Math.exp(-1 * (userTime - currentTime)));
            }

            log.info("현재 사용자 및 진행률: {}, {}", clientUserId, processPercent);
            QueueDTO dto = new QueueDTO(clientUserId, processPercent);

            if (emitter != null) {
                try {
                    String response = objectMapper.writeValueAsString(dto);
                    emitter.send(SseEmitter.event().name("queue").data(response));
                    log.info("SSE 메시지 전송: userId={}, message={}", clientUserId, response);

                    // SSE 연결 종료 책임은 클라이언트에게
                } catch (Exception e) {
                    log.error("SSE 전송 실패: userId={}, error={}", clientUserId, e.getMessage());
                    emitters.remove(clientUserId); // 전송 실패 시 제거
                }
            } else {
                log.warn("SSEEmitter 없음, 메시지 저장: userId={}", clientUserId);
                records.put(clientUserId, dto);
            }
        });
    }

    private long extractTimeStamp(String messageId) {
        Pattern pattern = Pattern.compile("^(\\d+)-");
        Matcher matcher = pattern.matcher(messageId);

        if (matcher.find()) {
            return Long.parseLong(matcher.group(1)); // 타임스탬프 부분
        }

        return 0L;
    }
}
