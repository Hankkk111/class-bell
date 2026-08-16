const DAY_NAMES = ["", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const START_HOUR = 8;   // timetable grid range: 8:00 - 22:00
const END_HOUR = 22;
const ROW_HEIGHT = 44;  // must match .tt-hour / .tt-cell height in style.css

let allCourses = [];

function tickClock() {
  const now = new Date();
  const hh = String(now.getHours()).padStart(2, "0");
  const mm = String(now.getMinutes()).padStart(2, "0");
  const ss = String(now.getSeconds()).padStart(2, "0");
  document.getElementById("liveClock").textContent = `${hh}:${mm}:${ss}`;
}
setInterval(tickClock, 1000);
tickClock();

async function fetchJson(url, options) {
  const res = await fetch(url, options);
  if (!res.ok) {
    const err = await res.json().catch(() => ({ error: "Request failed" }));
    throw new Error(err.error || "Request failed");
  }
  return res.json();
}

async function loadAll() {
  try {
    const [courses, dashboard] = await Promise.all([
      fetchJson("/api/courses"),
      fetchJson("/api/dashboard"),
    ]);
    allCourses = courses;
    renderHero(dashboard);
    renderSuggestions(dashboard.suggestions || []);
    renderTimetable(courses, dashboard.todayDow);
    renderCourseList(courses);
  } catch (e) {
    console.error(e);
    document.getElementById("heroTitle").textContent = "Can't reach the server";
    document.getElementById("heroEyebrow").textContent = "Make sure the Java server is running";
  }
}

function renderHero(dash) {
  const eyebrow = document.getElementById("heroEyebrow");
  const title = document.getElementById("heroTitle");
  const meta = document.getElementById("heroMeta");
  const num = document.getElementById("countdownNum");
  const unit = document.getElementById("countdownUnit");

  if (!dash.nextClass) {
    eyebrow.textContent = "Schedule is empty";
    title.textContent = "No courses added yet";
    meta.textContent = "Add your first class using the form on the right";
    num.textContent = "--";
    unit.textContent = "";
    return;
  }

  const c = dash.nextClass;
  if (dash.inProgress) {
    eyebrow.textContent = "In progress";
    title.textContent = c.name;
    meta.textContent = `📍 ${c.location || "No location set"} · ${c.start}–${c.end}`;
    num.textContent = dash.minutesUntil;
    unit.textContent = "min left";
  } else {
    const dayTxt = dash.daysAhead === 0 ? "today" : (dash.daysAhead === 1 ? "tomorrow" : DAY_NAMES[c.day]);
    eyebrow.textContent = dash.daysAhead === 0 ? "Next class" : "Next class (not today)";
    title.textContent = c.name;
    meta.textContent = `📍 ${c.location || "No location set"} · ${dayTxt} ${c.start}–${c.end}`;
    if (dash.minutesUntil >= 60) {
      num.textContent = Math.floor(dash.minutesUntil / 60);
      unit.textContent = "hours to go";
    } else {
      num.textContent = dash.minutesUntil;
      unit.textContent = "min to go";
    }
  }
}

function renderSuggestions(list) {
  const stack = document.getElementById("suggestionStack");
  stack.innerHTML = "";
  if (list.length === 0) {
    stack.innerHTML = `<div class="sticky-note info">No suggestions</div>`;
    return;
  }
  list.forEach((text) => {
    const div = document.createElement("div");
    let cls = "sticky-note";
    if (text.startsWith("Running late")) cls += " urgent";
    else if (text.startsWith("Only") || text.startsWith("Get to") || text.startsWith("Switch")) cls += " info";
    div.className = cls;
    div.textContent = text;
    stack.appendChild(div);
  });
}

function renderTimetable(courses, todayDow) {
  const grid = document.getElementById("timetableGrid");
  grid.innerHTML = "";

  const hourCount = END_HOUR - START_HOUR;
  grid.style.gridTemplateRows = `auto repeat(${hourCount}, ${ROW_HEIGHT}px)`;

  const corner = document.createElement("div");
  corner.className = "tt-corner";
  grid.appendChild(corner);

  for (let d = 1; d <= 7; d++) {
    const el = document.createElement("div");
    el.className = "tt-daylabel" + (d === todayDow ? " is-today" : "");
    el.textContent = DAY_NAMES[d];
    grid.appendChild(el);
  }

  for (let h = 0; h < hourCount; h++) {
    const label = document.createElement("div");
    label.className = "tt-hour";
    label.textContent = `${START_HOUR + h}:00`;
    label.style.gridColumn = "1";
    label.style.gridRow = String(h + 2);
    grid.appendChild(label);

    for (let d = 1; d <= 7; d++) {
      const cell = document.createElement("div");
      cell.className = "tt-cell" + (d === todayDow ? " is-today-col" : "");
      cell.style.gridColumn = String(d + 1);
      cell.style.gridRow = String(h + 2);
      grid.appendChild(cell);
    }
  }

  // One positioned wrapper per day, used to place course blocks absolutely
  const dayWrappers = {};
  for (let d = 1; d <= 7; d++) {
    const wrap = document.createElement("div");
    wrap.style.gridColumn = String(d + 1);
    wrap.style.gridRow = `2 / ${hourCount + 2}`;
    wrap.style.position = "relative";
    grid.appendChild(wrap);
    dayWrappers[d] = wrap;
  }

  const now = new Date();
  const nowMinutes = now.getHours() * 60 + now.getMinutes();

  courses.forEach((c) => {
    const wrap = dayWrappers[c.day];
    if (!wrap) return;
    const startMin = timeToMinutes(c.start);
    const endMin = timeToMinutes(c.end);
    const top = ((startMin - START_HOUR * 60) / 60) * ROW_HEIGHT;
    const height = Math.max(((endMin - startMin) / 60) * ROW_HEIGHT, 20);

    const block = document.createElement("div");
    block.className = "tt-course";
    if (c.day === todayDow && endMin < nowMinutes) block.classList.add("is-past");
    block.style.top = `${top}px`;
    block.style.height = `${height}px`;
    block.innerHTML = `<span class="c-name">${escapeHtml(c.name)}</span><span class="c-loc">${escapeHtml(c.location || "")}</span>`;
    wrap.appendChild(block);
  });

  if (todayDow >= 1 && todayDow <= 7 && dayWrappers[todayDow]) {
    const gridStart = START_HOUR * 60, gridEnd = END_HOUR * 60;
    if (nowMinutes >= gridStart && nowMinutes <= gridEnd) {
      const line = document.createElement("div");
      line.className = "tt-now-line";
      line.style.top = `${((nowMinutes - gridStart) / 60) * ROW_HEIGHT}px`;
      dayWrappers[todayDow].appendChild(line);
    }
  }
}

function timeToMinutes(t) {
  const [h, m] = t.split(":").map(Number);
  return h * 60 + m;
}

function renderCourseList(courses) {
  const ul = document.getElementById("courseList");
  ul.innerHTML = "";
  if (courses.length === 0) {
    ul.innerHTML = `<li class="cl-empty">No courses yet — add one above</li>`;
    return;
  }
  const sorted = [...courses].sort((a, b) => a.day - b.day || a.start.localeCompare(b.start));
  sorted.forEach((c) => {
    const li = document.createElement("li");
    li.innerHTML = `
      <div class="cl-info">
        <strong>${escapeHtml(c.name)}</strong>
        <span class="cl-sub">${DAY_NAMES[c.day]} ${c.start}-${c.end} ${c.location ? "· " + escapeHtml(c.location) : ""}</span>
      </div>
      <button class="cl-del" title="Delete" data-id="${c.id}">✕</button>
    `;
    ul.appendChild(li);
  });

  ul.querySelectorAll(".cl-del").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const id = btn.getAttribute("data-id");
      btn.disabled = true;
      try {
        await fetchJson(`/api/courses?id=${id}`, { method: "DELETE" });
        await loadAll();
      } catch (e) {
        alert("Delete failed: " + e.message);
        btn.disabled = false;
      }
    });
  });
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

document.getElementById("addForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const msg = document.getElementById("formMsg");
  msg.textContent = "";
  msg.className = "form-msg";

  const payload = {
    name: document.getElementById("f_name").value.trim(),
    day: document.getElementById("f_day").value,
    start: document.getElementById("f_start").value,
    end: document.getElementById("f_end").value,
    location: document.getElementById("f_location").value.trim(),
    teacher: document.getElementById("f_teacher").value.trim(),
  };

  if (!payload.name) {
    msg.textContent = "Course name is required";
    msg.className = "form-msg error";
    return;
  }
  if (payload.end <= payload.start) {
    msg.textContent = "End time must be after start time";
    msg.className = "form-msg error";
    return;
  }

  try {
    await fetchJson("/api/courses", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    msg.textContent = "Added ✓";
    document.getElementById("addForm").reset();
    document.getElementById("f_start").value = "09:00";
    document.getElementById("f_end").value = "10:30";
    await loadAll();
  } catch (e) {
    msg.textContent = "Add failed: " + e.message;
    msg.className = "form-msg error";
  }
});

loadAll();
setInterval(loadAll, 30000); // refresh countdown/suggestions/now-line every 30s
