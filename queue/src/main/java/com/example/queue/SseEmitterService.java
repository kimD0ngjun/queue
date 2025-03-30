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
    private Map<String, Long> messageTimestamps = new ConcurrentHashMap<>();
    private ObjectMapper objectMapper = new ObjectMapper();

    private final RedisTemplate<String, String> redisTemplate;

    // 사용자별 SSE 연결 생성
    public SseEmitter createEmitter(String userId) {
        SseEmitter emitter = new SseEmitter(60_000L); // 60초 타임아웃
        emitters.put(userId, emitter);

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

        log.info("emitter 임시 저장소 크기: {}", emitters.size());

        if (!emitters.containsKey(userId)) {
            log.info("아직 {} SSE Emitter 생성 안됨", userId);
            QueueDTO dto = new QueueDTO(userId, 100);
            records.put(userId, dto);
        }
        emitters.forEach((clientUserId, emitter) -> {
//            String userMessageId = getMessageId(clientUserId);
//            long currentTimeStamp = extractTimeStamp(messageId);
//            long userTimeStamp = extractTimeStamp(userMessageId);
//
//            int percent = (userTimeStamp != 0L) ? (int) (((double) currentTimeStamp / userTimeStamp) * 100) : 0;
            QueueDTO dto = new QueueDTO(clientUserId, 100);

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

    private int getQueuePercentage(String userId) {
        long messageTimeStamp;

        if (messageTimestamps.containsKey(userId)) {
            messageTimeStamp = messageTimestamps.get(userId);
        } else {
            PendingMessages pendingMessages = redisTemplate.opsForStream()
                .pending(STREAM_KEY, Consumer.from(CONSUMER_GROUP, CONSUMER_NAME), Range.unbounded(), 10000L);
        }

        return 0;
    }

//
//    private String getMessageId(String userId) {
//        String userMessageId = messageIds.get(userId);
//        if (userMessageId != null) {
//            return userMessageId;
//        }
//
//        PendingMessages pendingMessages = redisTemplate.opsForStream()
//                .pending(STREAM_KEY, Consumer.from(CONSUMER_GROUP, CONSUMER_NAME), Range.unbounded(), 10000L);
//
//        PendingMessage userPendingMessage = pendingMessages.stream().filter(
//                pendingMessage -> {
//                    String messageId = pendingMessage.getIdAsString();
//                    log.info("대기 메세지 식별값 ID: {}", messageId);
//
//                    List<MapRecord<String, Object, Object>> messages = redisTemplate.opsForStream()
//                            .range(STREAM_KEY, Range.closed(messageId, messageId));
//
//                    assert messages != null;
//                    if (!messages.isEmpty()) {
//                        MapRecord<String, Object, Object> messageValue = messages.getFirst();
//                        log.info("메세지 내용: {}", messageValue);
//                        String messageUserid = (String) messageValue.getValue().get("userId");
//                        log.info("클라이언트용 식별값(사용자 ID): {}", messageUserid);
//
//                        return userId.equals(messageUserid);
//                    }
//
//                    return false;
//                }
//        ).findFirst().orElse(null);
//
//        assert userPendingMessage != null;
//        userMessageId = userPendingMessage.getId().getValue();
//        messageIds.put(userId, userMessageId);
//
//        return userMessageId;
//    }
//
    private long extractTimeStamp(String messageId) {
        Pattern pattern = Pattern.compile("^(\\d+)-");
        Matcher matcher = pattern.matcher(messageId);

        if (matcher.find()) {
            return Long.parseLong(matcher.group(1)); // 타임스탬프 부분
        }

        return 0L;
    }
}
