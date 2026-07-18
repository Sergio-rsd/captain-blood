# Деплой на VPS (systemd)

Сервис: `captainblood.Main` (HTTP API) + `captainblood.watchdog.WatchdogMain` (LLM-диагностика
при сбое `/health`). VPS: `31.97.125.243`, SSH: `ssh -i ~/.ssh/aeza_vps root@31.97.125.243`
(живые креды — только в локальном `resources/config.properties` и `/root/config.properties`
на VPS, здесь не хранятся).

## Отличие от предыдущей версии

Раньше сервис жил в `screen`-сессии, которая не переживала перезагрузку VPS. Теперь —
два systemd-юнита из `deploy/`:
- `captainblood.service` — сам чат-сервис, `Restart=always`, переживает и ребут, и
  обычное падение процесса.
- `captainblood-watchdog.service` + `captainblood-watchdog.timer` — разовый запуск
  watchdog'а раз в 5 минут, диагностирует и чинит то, что голый `Restart=always` не
  может (например, упавшую отдельно Ollama).

## 1. Сборка

```powershell
cd C:\Projects\CaptainBlood
.\gradlew.bat jar
```

Результат — `build/libs/CaptainBlood-1.0.0.jar` (fat jar, все зависимости внутри).

## 2. Перенос на VPS

```bash
scp -i ~/.ssh/aeza_vps build/libs/CaptainBlood-1.0.0.jar root@31.97.125.243:/root/
scp -i ~/.ssh/aeza_vps adventures_sea.db root@31.97.125.243:/root/
scp -i ~/.ssh/aeza_vps deploy/captainblood.service deploy/captainblood-watchdog.service deploy/captainblood-watchdog.timer root@31.97.125.243:/etc/systemd/system/
```

`config.properties` НЕ переносится этим шагом (секреты) — редактируется на VPS вручную,
одноразово при первом деплое или при смене ключей/пароля (см. ниже).

## 3. Конфигурация на VPS

```bash
ssh -i ~/.ssh/aeza_vps root@31.97.125.243 "cat > /root/config.properties << 'EOF'
API_KEY=<ключ облачного роутера>
BASE_URL=<base url облачного роутера>
PRIVATE_LLM_LOGIN=<логин>
PRIVATE_LLM_PASSWORD=<пароль>
SHOW_SOURCES_IN_UI=false
WATCHDOG_MODEL=deepseek/deepseek-v4-flash РАБОЧАЯ
WATCHDOG_HEALTH_URL=http://localhost:8080/health
EOF"
```

Важно: `config.properties` должен лежать в `/root/` (рабочая директория обоих
systemd-юнитов, `WorkingDirectory=/root`) — `loadConfig()` ищет файл в classpath jar'а,
а classpath fat-jar собирается процессом `java -jar`, который резолвит относительные
пути от текущей рабочей директории процесса, не от расположения jar-файла. Если файла
там нет — сервис откажется стартовать (`PRIVATE_LLM_LOGIN`/`PASSWORD` не заданы), а
watchdog не сможет вызвать облачную LLM.

## 4. Установка systemd-юнитов

```bash
ssh -i ~/.ssh/aeza_vps root@31.97.125.243 "
systemctl daemon-reload
systemctl enable --now captainblood.service
systemctl enable --now captainblood-watchdog.timer
systemctl status captainblood.service --no-pager
systemctl list-timers captainblood-watchdog.timer --no-pager
"
```

## 5. Проверка

```bash
curl -s http://31.97.125.243:8080/health
curl -s -o /dev/null -w "%{http_code}\n" -u "<логин>:<пароль>" http://31.97.125.243:8080/
```

## 6. Обновление после правок кода

```powershell
cd C:\Projects\CaptainBlood
.\gradlew.bat jar
```

```bash
scp -i ~/.ssh/aeza_vps build/libs/CaptainBlood-1.0.0.jar root@31.97.125.243:/root/
ssh -i ~/.ssh/aeza_vps root@31.97.125.243 "systemctl restart captainblood.service"
```

Юнит-файлы (`.service`/`.timer`) обновляются на VPS только если менялись сами (после
правки — `scp` + `systemctl daemon-reload` + `systemctl restart`/`systemctl restart
captainblood-watchdog.timer`).

## 7. Живой тест watchdog'а (сценарий сбоя)

```bash
ssh -i ~/.ssh/aeza_vps root@31.97.125.243 "
systemctl stop ollama
sleep 5
curl -s http://localhost:8080/health   # ожидается 503 ollama_unreachable
systemctl start captainblood-watchdog.service   # форсировать немедленный тик, не ждать таймер
sleep 20
cat /root/captainblood-watchdog.log
curl -s http://localhost:8080/health   # ожидается снова ok
"
```

## Логи и диагностика

- Сервис: `journalctl -u captainblood.service -f` (или `/root/captainblood.log`).
- Watchdog: `/root/captainblood-watchdog.log` (диагноз + применённое действие на
  каждый сбойный тик; здоровые тики ничего не пишут — облачная LLM вызывается только
  при реальном сбое, чтобы не платить за пустые проверки).
- Circuit breaker: `/root/captainblood-watchdog.state.json` — счётчик подряд неудачных
  попыток восстановления; 3+ подряд отключают автоприменение действий до ручного
  сброса (удалить файл или поправить `consecutiveFailures` на `0`).

## Безопасность секретов

`config.properties` (локально и на VPS) не коммитится и не переносится в
документацию/runbook'и — только процедура и то, ГДЕ искать актуальное значение.
