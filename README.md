# Spring Config Table (SCT)

Markdown 테이블로 Spring Boot 멀티 환경 YAML 설정 파일을 관리하는 도구.

하나의 마스터 Markdown 파일에서 모든 환경(dev, beta, real, gov, ...)의 설정을 한눈에 보고 편집할 수 있으며, 환경별 `application-{profile}.yml` 파일을 자동 생성합니다.

## 주요 기능

- **Markdown → YAML 생성** — 마스터 Markdown 테이블에서 환경별 YAML 자동 생성 (주석 포함)
- **YAML → Markdown 마이그레이션** — 기존 YAML 파일들을 마스터 Markdown으로 일괄 변환 (주석 보존)
- **YAML Lens** — YAML 또는 마스터 Markdown 파일을 선택해 프로퍼티/프로필/값을 테이블로 검색, 더블클릭 소스 네비게이션, CSV 내보내기
- **비주얼 테이블 에디터** — Master-Detail 레이아웃으로 프로퍼티를 그룹별로 탐색/검색, 환경별 값과 주석을 편집
- **자동 감지** — 마스터 파일 변경 시 YAML 자동 재생성 (IntelliJ / Maven)
- **멀티 모듈** — 프로젝트 내 여러 모듈별 마스터 파일/출력 경로 설정
- **Spring 메타데이터 연동** — `spring-configuration-metadata.json` 기반 타입 감지, 자동완성, unknown property 경고

## 마스터 Markdown 포맷

```markdown
## server

| env      | port                         | host                        |
|----------|------------------------------|-----------------------------|
| _default | 8080 <!-- HTTP 서버 포트 -->  | localhost <!-- 바인드 주소 --> |
| beta     | 9090                         |                             |
| real     | 80 <!-- 운영 포트 -->         | 0.0.0.0                     |

## spring.datasource

| env      | url                          | username |
|----------|------------------------------|----------|
| _default | jdbc:mysql://localhost/db     | root     |
| beta     | jdbc:mysql://beta-db/db      |          |
| real     | jdbc:mysql://real-db/db      | admin    |
```

- `_default` = `application.yml` (기본 프로필)
- 빈 셀 = default 값 상속
- `null` = 명시적 null 오버라이드
- `"값"` = 강제 문자열 (숫자나 boolean처럼 보이는 문자열 보존)
- `<!-- 주석 -->` = YAML 주석으로 변환 (환경별 다른 주석 가능)

## 모듈 구성

| 모듈 | 설명 |
|------|------|
| `sct-core` | 파서, 라이터, 익스포터, 임포터 (핵심 라이브러리) |
| `sct-maven-plugin` | Maven `generate-resources` 페이즈 통합 |
| `sct-intellij-plugin` | IntelliJ 플러그인 (에디터, YAML Lens, 마이그레이션, 자동 생성) |

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

#### 비주얼 테이블 에디터

`master-config.md` 파일을 열면 에디터 하단에 **Table** 탭이 나타납니다. 또는 우클릭 → **Open Table Editor**.

- **왼쪽 패널**: 프로퍼티를 그룹별로 표시 (datasource, rabbitmq, ...) + 검색 필터
- **오른쪽 패널**: 선택한 프로퍼티의 환경별 값 + 주석을 세로 폼으로 편집
- `●` 표시 = 환경 오버라이드가 있는 프로퍼티
- Spring 메타데이터 기반 타입 감지 (메타데이터 없으면 값에서 자동 감지)
- Add/Rename/Delete Property, Section, Environment 지원

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
3. 저장 위치 선택 → 마스터 Markdown 생성 (주석 포함)

#### 자동 YAML 생성

1. **Settings > Tools > Spring Config Table** 에서 매핑 설정 (마스터 파일 경로 ↔ 출력 디렉토리)
2. 마스터 파일 저장 시 자동 YAML 생성 (500ms debounce)
3. **Tools > Generate YAML from Master Markdown** 으로 수동 실행

#### 환경 정렬 설정

Settings에서 두 가지 정렬 순서를 설정합니다:

- **Lifecycle order**: `default, local, dev, alpha, beta, beta-dr, real, release, dr`
- **Region order**: `gov, ncgn, ngcc, ngsc, ninc, ngovc, ngoic`

결과: base 그룹 → gov 그룹 → ncgn 그룹 → ... 순서로, 각 그룹 내에서 lifecycle 순서 적용.

## 이스케이프 규칙

| 문자 | Markdown 표기 | 설명 |
|------|--------------|------|
| `\|` | `\|` | 파이프 (셀 구분자 충돌 방지) |
| `\n` | `\n` | 줄바꿈 |
| `\\` | `\\` | 백슬래시 |
| `\"` | `\"` | 따옴표 (quoted string 내부) |
| `<!-- -->` | `<!-- 주석 -->` | YAML 주석 (환경별) |

## 주석 round-trip

```
YAML 원본                           → Markdown                                → YAML 생성
port: 8080  # HTTP 서버 포트         → | 8080 <!-- HTTP 서버 포트 --> |         → port: 8080 # HTTP 서버 포트
```

- YAML `# 주석` → Markdown `<!-- 주석 -->` (마이그레이션 시)
- Markdown `<!-- 주석 -->` → YAML `# 주석` (생성 시)
- 환경별로 다른 주석 가능

## 알려진 제약사항

- 섹션 정렬: `server` → `spring` → `management` → `springdoc` → 나머지 알파벳순
- 섹션 이름의 첫 번째 dot 앞까지가 YAML 최상위 키: `## com.example.config` → section=`com`, prefix=`example.config`
- `---` 멀티 도큐먼트 YAML은 import 시 모든 도큐먼트가 병합됩니다
- 앞뒤 공백이 중요한 값은 자동으로 따옴표 처리됩니다
- YAML 주석 추출은 best-effort (inline `#` 주석만, 블록 주석 미지원)

## 테스트

```bash
mvn -pl sct-core test
```

70개 테스트: round-trip (파이프, 줄바꿈, 백슬래시, 따옴표, null, boolean/숫자형 문자열, 공백, 주석), 멀티 도큐먼트 YAML, top-level scalar, E2E 파이프라인, 환경 정렬

## 라이선스

PinkMandarin
