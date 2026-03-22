# Spring Config Table (SCT)

Markdown 테이블로 Spring Boot 멀티 환경 YAML 설정 파일을 관리하는 도구.

하나의 마스터 Markdown 파일에서 모든 환경(dev, beta, real, gov, ...)의 설정을 한눈에 보고 편집할 수 있으며, 환경별 `application-{profile}.yml` 파일을 자동 생성합니다.

## 주요 기능

- **Markdown → YAML 생성** — 마스터 Markdown 테이블에서 환경별 YAML 자동 생성
- **YAML → Markdown 마이그레이션** — 기존 YAML 파일들을 마스터 Markdown으로 일괄 변환
- **YAML Lens** — YAML 또는 마스터 Markdown 파일을 선택해 프로퍼티/프로필/값을 테이블로 검색, 더블클릭 소스 네비게이션, CSV 내보내기
- **자동 감지** — 마스터 파일 변경 시 YAML 자동 재생성 (IntelliJ / Maven)
- **멀티 모듈** — 프로젝트 내 여러 모듈별 마스터 파일/출력 경로 설정

## 마스터 Markdown 포맷

```markdown
## server

| env | port | host |
|-----|------|------|
| _default | 8080 | localhost |
| beta | 9090 | |
| real | 80 | 0.0.0.0 |

## spring.datasource

| env | url | username |
|-----|-----|----------|
| _default | jdbc:mysql://localhost/db | root |
| beta | jdbc:mysql://beta-db/db | |
| real | jdbc:mysql://real-db/db | admin |
```

- `_default` = `application.yml` (기본 프로필)
- 빈 셀 = default 값 상속
- `null` = 명시적 null 오버라이드
- `"값"` = 강제 문자열 (숫자나 boolean처럼 보이는 문자열 보존)

## 모듈 구성

| 모듈 | 설명 |
|------|------|
| `sct-core` | 파서, 라이터, 익스포터, 임포터 (핵심 라이브러리) |
| `sct-maven-plugin` | Maven `generate-resources` 페이즈 통합 |
| `sct-intellij-plugin` | IntelliJ 플러그인 (YAML Lens, 마이그레이션, 자동 생성, 멀티 모듈) |

## 빠른 시작

### 요구사항

- Java 21+
- Maven 3.9+

### 빌드

```bash
# Maven 모듈 빌드 + 로컬 설치
mvn clean install

# IntelliJ 플러그인 빌드 (sct-core가 mavenLocal에 있어야 함)
cd sct-intellij-plugin
./gradlew buildPlugin
```

빌드 결과:
- IntelliJ 플러그인 ZIP: `sct-intellij-plugin/build/distributions/sct-intellij-plugin-1.0.0-SNAPSHOT.zip`

### Maven 플러그인

```xml
<plugin>
    <groupId>com.pinkmandarin</groupId>
    <artifactId>sct-maven-plugin</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <configuration>
        <masterFile>${project.basedir}/master-config.md</masterFile>
    </configuration>
    <executions>
        <execution>
            <goals><goal>generate</goal></goals>
        </execution>
    </executions>
</plugin>
```

`mvn compile` 이상 실행 시 `master-config.md` → `src/main/resources/application*.yml` 자동 생성.

### IntelliJ 플러그인

**설치:** Settings > Plugins > Install Plugin from Disk 에서 ZIP 설치

#### YAML Lens (YAML 뷰어)

1. Project View에서 YAML 파일, Markdown 마스터 파일, 또는 디렉토리 선택
2. 우클릭 → **YAML Lens**
3. 프로퍼티/값/프로필 실시간 검색, 자연순 정렬 (1, 2, 10 — 사전순 아님)
4. 더블클릭 → 소스 파일 해당 라인으로 네비게이션
5. CSV 내보내기
6. 모달리스 — 에디터와 동시 사용 가능

#### YAML → Markdown 마이그레이션

1. Project View에서 `application*.yml` 파일들 선택
2. 우클릭 → **YAML → 마스터 Markdown 변환**
3. 저장 위치 선택 → 마스터 Markdown 생성

#### 자동 YAML 생성

1. **Settings > Tools > Spring Config Table** 에서 매핑 설정 (마스터 파일 경로 ↔ 출력 디렉토리)
2. 마스터 파일 저장 시 자동 YAML 생성 (500ms debounce)
3. **Tools > Generate YAML from Master Markdown** 으로 수동 실행

멀티 모듈 프로젝트에서 모듈별로 다른 마스터 파일/출력 경로를 +/- 버튼으로 설정할 수 있습니다.

## 이스케이프 규칙

마스터 Markdown 테이블 값에서 특수 문자는 자동 이스케이프됩니다:

| 문자 | Markdown 표기 | 설명 |
|------|--------------|------|
| `\|` | `\|` | 파이프 (셀 구분자 충돌 방지) |
| `\n` | `\n` | 줄바꿈 |
| `\\` | `\\` | 백슬래시 |
| `\"` | `\"` | 따옴표 (quoted string 내부) |

## 알려진 제약사항

- 섹션 정렬 순서: `server` → `spring` → `management` → `springdoc` → 나머지 알파벳순
- 섹션 이름에 dot이 포함된 경우 (예: `com.example`), 첫 번째 dot 앞까지가 YAML 최상위 키로 사용됩니다. `## com.example.config` → section=`com`, prefix=`example.config`
- `---` 멀티 도큐먼트 YAML은 import 시 모든 도큐먼트가 병합됩니다
- Markdown 테이블 셀의 앞뒤 공백은 trim됩니다. 공백이 중요한 값은 자동으로 따옴표 처리됩니다

## 테스트

```bash
mvn -pl sct-core test
```

63개 테스트가 핵심 기능을 커버합니다:
- Parser/Writer round-trip (파이프, 줄바꿈, 백슬래시, 따옴표, null, boolean/숫자형 문자열, 공백)
- 멀티 도큐먼트 YAML import
- Top-level scalar 값 round-trip
- 전체 파이프라인 E2E (Markdown → YAML 생성 + profile activation)

## 라이선스

PinkMandarin
