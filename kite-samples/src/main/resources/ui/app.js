const chat = document.getElementById("chat");
const form = document.getElementById("composer");
const input = document.getElementById("input");
const sendBtn = document.getElementById("send");
const banner = document.getElementById("banner");

let conversationId = sessionStorage.getItem("kite-conv") || null;

form.addEventListener("submit", (e) => {
  e.preventDefault();
  const text = input.value.trim();
  if (!text) return;
  input.value = "";
  void send(text);
});

input.addEventListener("keydown", (e) => {
  if (e.key === "Enter" && !e.shiftKey) {
    e.preventDefault();
    form.requestSubmit();
  }
});

async function send(text) {
  hideBanner();
  addMessage("user", text);
  const { bubble, events } = addAssistantSlot();
  sendBtn.disabled = true;

  try {
    const resp = await fetch("/api/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ message: text, conversationId }),
    });
    if (!resp.ok || !resp.body) {
      showBanner(`Request failed: ${resp.status} ${resp.statusText}`);
      return;
    }
    await consumeSse(resp.body, bubble, events);
  } catch (err) {
    showBanner(`Network error: ${err.message}`);
  } finally {
    sendBtn.disabled = false;
    input.focus();
  }
}

async function consumeSse(body, bubble, events) {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    let idx;
    while ((idx = buffer.indexOf("\n\n")) !== -1) {
      const frame = buffer.slice(0, idx);
      buffer = buffer.slice(idx + 2);
      const parsed = parseFrame(frame);
      if (parsed) handleEvent(parsed.event, parsed.data, bubble, events);
    }
  }
}

function parseFrame(frame) {
  let event = "message";
  const dataLines = [];
  for (const line of frame.split("\n")) {
    if (line.startsWith(":")) continue; // SSE comment
    if (line.startsWith("event:")) event = line.slice(6).trim();
    else if (line.startsWith("data:")) dataLines.push(line.slice(5).trim());
  }
  if (dataLines.length === 0) return null;
  try {
    return { event, data: JSON.parse(dataLines.join("\n")) };
  } catch {
    return null;
  }
}

function handleEvent(event, data, bubble, events) {
  switch (event) {
    case "delta":
      bubble.textContent += data.text ?? "";
      scrollToBottom();
      break;
    case "tool_call":
      appendEvent(events, `→ ${data.name}(${formatArgs(data.args)})`, "tool");
      break;
    case "tool_result":
      appendEvent(events, `✓ ${data.name}  (${data.elapsedMs}ms)`, "tool");
      break;
    case "transfer":
      appendEvent(events, `↪ transfer ${data.from} → ${data.to}`);
      break;
    case "blocked":
      showBanner(`Blocked by ${data.guard}: ${data.info?.message ?? ""}`);
      bubble.remove();
      break;
    case "done":
      if (data.conversationId) {
        conversationId = data.conversationId;
        sessionStorage.setItem("kite-conv", conversationId);
      }
      if (data.status && data.status !== "OK") {
        appendEvent(events, `done: ${data.status}`);
      }
      appendEvent(events, `· ${data.tokens ?? 0} tokens`);
      break;
    case "error":
      showBanner(`Error: ${data.message}`);
      bubble.remove();
      break;
    default:
      break;
  }
}

function formatArgs(args) {
  if (args == null || typeof args !== "object") return "";
  const parts = [];
  for (const [k, v] of Object.entries(args)) {
    parts.push(`${k}=${JSON.stringify(v)}`);
  }
  return parts.join(", ");
}

function addMessage(role, text) {
  const el = document.createElement("div");
  el.className = `msg ${role}`;
  el.textContent = text;
  chat.appendChild(el);
  scrollToBottom();
  return el;
}

function addAssistantSlot() {
  const bubble = addMessage("assistant", "");
  const events = document.createElement("div");
  events.className = "events";
  chat.appendChild(events);
  return { bubble, events };
}

function appendEvent(host, text, cls) {
  const ev = document.createElement("div");
  ev.className = "ev" + (cls ? " " + cls : "");
  ev.textContent = text;
  host.appendChild(ev);
  scrollToBottom();
}

function showBanner(text) {
  banner.textContent = text;
  banner.classList.remove("hidden");
}
function hideBanner() {
  banner.textContent = "";
  banner.classList.add("hidden");
}

function scrollToBottom() {
  chat.scrollTop = chat.scrollHeight;
}
