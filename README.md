# Captain Blood

Приватный LLM-чат-сервис "капитан Блад" — эрудит-бот про море/пиратов/океан (RAG по
корпусу `adventures_sea.db`, локальная модель через Ollama), развёрнутый на VPS для
мальчиков и девочек 12+. Плюс — watchdog с LLM-диагностикой, который восстанавливает
сервис при сбое `/health`, а не просто перезапускает его по таймеру.

Самостоятельный проект: чат-сервис на VPS + watchdog, который следит за его здоровьем
и восстанавливает при сбое.

## Состав

- **`captainblood.Main`** — сам чат-сервис: HTTP-сервер (`/chat`, `/health`, HTML-морда),
  RAG-поиск по `adventures_sea.db`, обращение к локальной Ollama. Запуск:
  `java -jar CaptainBlood-1.0.0.jar --server` (для systemd, без интерактивного меню)
  или без аргументов (интерактивное меню — для локальной разработки).
- **`captainblood.watchdog.WatchdogMain`** — разовый запуск watchdog'а (см. ниже).
  Запуск: `java -cp CaptainBlood-1.0.0.jar captainblood.watchdog.WatchdogMainKt`.

## Watchdog — LLM-диагностика при сбое

Проблема: сервис раньше жил в `screen`-сессии, которая не переживала перезагрузку VPS —
Ollama поднималась сама (свой systemd), а чат-сервис оставался мёртвым незаметно.

Решение — два слоя:
1. **Сам сервис теперь под systemd** (`captainblood.service`, `Restart=always`) —
   переживает и ребут, и обычное падение процесса.
2. **Watchdog** (`captainblood-watchdog.timer`, каждые 5 минут) — на случай, когда
   голого рестарта недостаточно (например, `/health` живой, но отвечает
   `ollama_unreachable`, потому что упала сама Ollama — отдельный systemd-юнит, рестарт
   captainblood его не чинит). При сбое `/health`:
   - собирает локальную диагностику (`uptime`, `systemctl status ollama`,
     `systemctl status captainblood`, `ss -tlnp`) — без SSH, всё локально на той же
     машине;
   - просит облачную LLM поставить диагноз и выбрать ОДНО действие из строгого списка
     (`RESTART_OLLAMA` / `RESTART_CAPTAINBLOOD_SERVICE` / `RESTART_BOTH` /
     `MANUAL_REQUIRED`) — LLM не пишет shell-команду текстом, только выбирает из меню;
     саму команду для каждого варианта заранее написал и провалидировал код
     (`RecoveryAction.kt`), это исключает риск command injection;
   - применяет действие (кроме `MANUAL_REQUIRED` и кроме случая, когда сработал
     circuit breaker — 3 подряд неудачные попытки восстановления подряд отключают
     автоприменение до ручного вмешательства, `WatchdogState.kt`);
   - перепроверяет `/health` и пишет строку в `captainblood-watchdog.log`.

Локальный тест без риска для машины — флаг `--dry-run`: логирует диагноз и выбранное
действие, но не выполняет реальную shell-команду.

## Конфигурация

Скопировать `src/main/resources/config.properties.example` в
`src/main/resources/config.properties` и заполнить (см. комментарии в файле). Ключи:
`API_KEY`/`BASE_URL` (облачный роутер, нужен только watchdog'у для диагностики),
`PRIVATE_LLM_LOGIN`/`PRIVATE_LLM_PASSWORD` (Basic Auth чата), `WATCHDOG_MODEL`/
`WATCHDOG_HEALTH_URL`.

## Деплой на VPS

См. `docs/VPS_Deploy_CaptainBlood.md`.

## CI — автоматическое ревью PR

Каждый PR ревьюится автоматически движком DevAssistant
(`.github/workflows/pr-review.yml`) — комментарий с находками появляется в PR после
`opened`/`synchronize`/`reopened`. Секреты `LLM_API_KEY`/`LLM_BASE_URL` и переменная
`LLM_MODEL` заведены в Settings → Secrets and variables → Actions этого репозитория.
