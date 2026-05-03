# Sweep Autocomplete OpenAI Adapter

Локальный HTTP-адаптер для режима Sweep autocomplete local mode. Сервер принимает протокол плагина и проксирует генерацию в OpenAI-совместимый `/chat/completions` API.

## Запуск

```powershell
npm install
npm run build
npm start
```

Настройки читаются из `.env` в этой папке. Значение `PORT` должно совпадать с `Autocomplete local port` в настройках плагина. Порт также можно переопределить аргументом:

```powershell
node dist/index.js --port 8881
```

## Протокол

- `GET /` и `GET /health` возвращают health-check.
- `POST /backend/next_edit_autocomplete` принимает JSON `NextEditAutocompleteRequest`.
- Ответ отдаётся как JSONL, совместимый с `NextEditAutocompleteResponse`.
