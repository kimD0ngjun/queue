# 대기열 구현
---
## 아키텍처
<img width="608" alt="스크린샷 2025-03-31 오전 12 16 44" src="https://github.com/user-attachments/assets/1fe025bc-fa73-494e-bc97-7cdf600ea262" />

## 사용 스택
- `Spring Boot` : 트래픽 수용 서버와 대기열 처리 서버 구현, Redis Stream 메세지 수신 및 SSE 연결
- `Redis` : Redis Stream 기반 대기열 큐 활용
- `SSE` : 클라이언트 실시간 처리 정보 전달
- `JavaScript` : SSE 연결 및 대기열 처리 결과 제공
- `JMeter` : 대용량 트래픽 테스트 툴

## 구현 결과
<img width="48%" height="288" alt="로딩바" src="https://github.com/user-attachments/assets/914f009c-66bb-487e-8db6-3ac93351b18a" /><img width="48%" height="288" alt="로그" src="https://github.com/user-attachments/assets/81ffb0c2-f6b5-45ce-8908-7a41e0299a3f" />
