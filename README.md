# AI Coding Agent (Android)

Мобильная IDE с автономным AI-агентом: Kotlin + Jetpack Compose (Material 3), OkHttp, kotlinx.serialization,
JGit, WorkManager, EncryptedSharedPreferences/Keystore, GitHub REST API, OpenRouter API.
Без Flutter/RN/WebView, без собственного backend, без обязательного root/Termux.

Цель по производительности: Realme C30S, Android 12 Go (2 ГБ RAM), minSdk 26.

## Архитектура

```
app/src/main/java/com/imnotahuman/codingagent/
  ui/         Compose-экраны, тема AMOLED + orange, AgentViewModel
  agent/      agent loop (AgentEngine), события сессии, подтверждения
  ai/         OpenRouter клиент (SSE streaming + tool calling)
  tools/      15 инструментов агента + реестр и JSON-схемы
  workspace/  проекты в app-specific storage, ленивое чтение файлов, unified-diff
  git/        JGit: clone/status/diff/add/commit/push/pull/branch/checkout/reset
  github/     GitHub REST: repos, workflow_dispatch, runs, jobs, logs, artifacts
  build/      BUILD APK пайплайн + WorkManager-поллинг с backoff
  security/   Keystore-хранилище секретов и редактирование секретов в выводе
  data/       настройки и персистентные сессии
```

Бизнес-логика вне Activity/Composable: Composable читают `StateFlow` из `AgentViewModel`,
вся работа идёт в `ViewModel`/сервисах на `Dispatchers.IO`.

## Agent loop

`User Prompt → AI (streaming) → Tool Call → Tool Result → AI → … → Final Answer`

Инструменты: `list_files`, `read_file`, `search_files`, `search_text`, `write_file`, `create_file`,
`delete_file`, `move_file`, `patch_file`, `run_command`, `git_status`, `git_diff`, `git_commit`,
`git_push`, `github_build`.

Ограничения: максимум шагов (по умолчанию 16), таймаут шага (120 с), обрезка вывода инструмента
(12 000 символов), кнопка **STOP** отменяет job на любом шаге. Chain-of-thought не отображается:
в ленте только статус, имя инструмента, аргументы и результат.

Опасные действия (`delete_file`, `git_push`/force, рискованные shell-команды) открывают диалог
подтверждения; отказ возвращается модели как tool result.

## Безопасность

- OpenRouter API key и GitHub PAT хранятся только в `EncryptedSharedPreferences` (AES256-GCM, мастер-ключ в Android Keystore).
- `usesCleartextTraffic=false`, OkHttp ограничен современными TLS-спеками — только HTTPS.
- `Redactor` вырезает секреты и `user:token@` из логов, tool output, git output и UI.
- Секреты не попадают в prompt: клиенты получают их через провайдер только для заголовка `Authorization`.
- PAT подставляется в URL только на время `clone`, затем remote переписывается без него.

## Сборка APK

Локально:

```
./gradlew assembleDebug        # если wrapper jar добавлен
gradle assembleDebug           # или системный Gradle 8.9+, JDK 17
```

В репозитории лежит один workflow: `.github/workflows/android.yml` — CI самого приложения
(debug APK → upload-artifact). Шаблон для пользовательских проектов лежит только в
`app/src/main/assets/build-apk.yml` и добавляется в открытый проект кнопкой **ADD WORKFLOW**.

Важно: `android-actions/setup-android` не используется — на `ubuntu-latest` Android SDK уже
установлен, а сам action падает при попытке поставить удалённый пакет `tools`.

> `gradle/wrapper/gradle-wrapper.jar` не включён в архив (бинарный файл).
> Выполните `gradle wrapper --gradle-version 8.9` один раз, либо используйте системный `gradle`.
> Workflow-файлы это учитывают и падают обратно на `gradle`.

## Сценарий приёмки

1. Установить APK.
2. Settings → OpenRouter API key → SAVE KEY, выбрать модель.
3. Settings → GitHub PAT (scope `repo` + `workflow`) → SAVE PAT → TEST GITHUB.
4. Settings → owner/repo/workflow/ref → SAVE REPO.
5. Projects → Clone GitHub Repository.
6. Agent → задача, например «add a Retry button to MainScreen».
7. Агент читает/меняет реальные файлы, каждый tool call и diff видны в ленте.
8. Git → COMMIT → PUSH (подтверждение push).
9. Build → BUILD APK → workflow_dispatch → polling с backoff → status/logs.
10. При ошибке — **ASK AI TO FIX BUILD**: лог передаётся агенту, он чинит проект.
11. При успехе артефакт APK скачивается в `Android/data/.../files/artifacts`.
