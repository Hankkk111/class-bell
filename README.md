# Class Bell

A personal course-schedule reminder web app, built with plain Java — only the
JDK's built-in `com.sun.net.httpserver`, no external dependencies (no Maven,
no Spring).

## Features

- Live countdown to the next class (or "in progress, X minutes left")
- Weekly timetable grid with a line marking the current time
- Time-aware suggestions based on the day's schedule — e.g. flagging when
  you're about to be late, when there's a long gap between classes worth
  using to study, or when the day is unusually packed
- Add / delete courses; data is saved to a local file and persists across
  restarts

## Project structure

```
class-bell/
├── src/Main.java        backend: HTTP server, course data, suggestion logic, persistence
├── public/
│   ├── index.html
│   ├── style.css
│   └── app.js
├── data/                 schedule.txt is created here on first run
└── README.md
```

## Running it

Requires JDK 11+. No Maven, Gradle, or internet connection needed.

```bash
cd class-bell
javac -d out src/Main.java
java -cp out Main
```

Then open <http://localhost:8080>.

## Implementation notes

- **HTTP server**: `com.sun.net.httpserver.HttpServer`, with separate
  handlers mounted per route (`/api/courses`, `/api/dashboard`, and a
  static-file handler for everything else).
- **Persistence**: a small pipe-delimited text file (`data/schedule.txt`) —
  no database, no external JSON library. `parseJsonObject()` is a minimal
  hand-written parser for the flat JSON objects the frontend submits.
- **Suggestion logic**: `buildSuggestions()` looks at the current time, the
  next class, and the gaps between today's classes to generate context-aware
  tips.

## Possible extensions

- Swap the flat-file storage for SQLite or a proper JSON store
- Support fortnightly (odd/even week) courses
- Desktop notifications instead of an in-browser reminder
- Multi-user support with authentication
