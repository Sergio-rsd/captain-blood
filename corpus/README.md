# corpus

Сырые исходники для пополнения базы знаний (`.txt`/`.md`/`.pdf`/`.docx`/`.doc`) перед
индексацией. Положите файл сюда, затем в клиент/админ-режиме (`java -jar
CaptainBlood-1.0.0.jar`, режим `[2]`):

```
!index-doc corpus/имя_файла.md
```

Команда почанкует файл, встроит эмбеддинги в `adventures_sea.db` и автоматически
синхронизирует базу на VPS (`syncDbToVps`). Для экспорта Telegram Desktop — та же
папка, команда `!index-telegram corpus/имя_файла.json`.
