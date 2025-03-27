package com.example.traffic;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/queue")
@RequiredArgsConstructor
public class TrafficController {

    private static final String STREAM_KEY = "queue";

    private final RedisTemplate<String, String> redisTemplate;

    @PostMapping("/join")
    public ResponseEntity<String> joinQueue(@RequestParam String userId) {
        RecordId recordId = redisTemplate.opsForStream()
                .add(STREAM_KEY, Map.of("userId", userId));

        return ResponseEntity.ok("대기열 큐 추가: " + recordId.getValue());
    }
}
