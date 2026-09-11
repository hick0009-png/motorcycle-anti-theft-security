# พรอมท์กู้คืน: ติดตั้ง OMP + Claude Bridge ให้พร้อมใช้ (Windows)

> วิธีใช้: ก๊อปทั้งไฟล์นี้วางให้ Claude Code (หรือ agent ที่มี shell) ในเครื่องที่ต้องการติดตั้ง
> อ้างอิงจากการติดตั้งจริงที่สำเร็จเมื่อ 2026-09-11 — รวมกับดักที่เจอมาแล้วทั้งหมด

---

## บริบทและเป้าหมาย

ติดตั้ง **OMP (Oh My Pi)** — terminal coding agent — ให้ใช้โมเดล Claude ผ่าน **subscription ที่มีอยู่** (ไม่ใช่ API credits) โดยเชื่อมผ่าน `omp-claude-bridge` และตั้งค่าให้ใช้งานได้ทันทีทุก session โดยไม่ต้องตั้งอะไรใหม่

ต้องได้ครบ: model routing, Kotlin LSP, DAP debugger, memory system, worktree subagents, MCP

**สภาพแวดล้อมอ้างอิง:** Windows 10, PowerShell 5.1, ผู้ใช้ `ASUS`, มี Android Studio + Android SDK, Git Bash, Python 3
**ถ้าเครื่องต่างออกไป** ให้ปรับ path เหล่านี้ทุกจุด: `C:\Users\ASUS`, `C:\Program Files\Android\Android Studio\jbr`, `C:\Python314\python.exe`

---

## ⚠️ อ่านก่อนเริ่ม: กับดัก 7 ข้อที่เสียเวลามาแล้ว

อย่าเดินซ้ำทางเหล่านี้:

1. **อย่าใช้ `@numbered/omp-claude-bridge`** (แพ็กเกจที่คู่มือเก่าระบุ) — build มาเพื่อ module scope `@earendil-works/*` แต่ OMP 18.x ใช้ `@oh-my-pi/*` โหลดไม่ขึ้นทั้ง `bun add` และ `omp plugin install`
2. **อย่าใช้ `irm https://omp.sh/install.ps1 | iex`** — ถูก classifier บล็อก และจริงๆ สคริปต์แค่รัน `bun install -g` ให้ ข้ามไปได้เลย
3. **อย่าใช้ kotlin-lsp ของ JetBrains** — เป็น EAP build ที่**หมดอายุ** ดาวน์โหลด 390 MB มาแล้วขึ้น "This build of intellij-server has expired"
4. **`omp plugin link` ใช้ไม่ได้บน Windows** — symlink EPERM ต้องเปิด Developer Mode ใช้ `config.yml` ชี้ path ตรงแทน
5. **อย่าชี้ DAP adapter ตรงๆ** — ต้องผ่าน shim ไม่งั้น attach ค้างถาวร (รายละเอียดในขั้นที่ 7)
6. **`RULES.md` ไม่ทำงานผ่าน bridge** — bridge อ่านเฉพาะ `AGENTS.md` ต้องเขียนกฎลงไฟล์นั้น
7. **`bun install --production` ยังดึง peer deps มา** — ไม่เป็นไร ปล่อยไว้ได้

---

## ขั้นที่ 0 — ตรวจสภาพแวดล้อม

```powershell
bun --version; omp --version; claude --version; claude whoami
Test-Path "C:\Program Files\Android\Android Studio\jbr\bin\java.exe"
Test-Path "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
(Get-Command python).Source
```

บันทึกว่าอะไรมี/ไม่มี แล้วข้ามขั้นที่ติดตั้งแล้วได้

---

## ขั้นที่ 1 — Bun

```powershell
powershell -c "irm bun.sh/install.ps1 | iex"
```

ติดตั้งไปที่ `C:\Users\ASUS\.bun\bin\bun.exe` และเพิ่ม PATH ให้เอง (ต้องการ ≥ 1.3.14)

---

## ขั้นที่ 2 — OMP

**ไม่ต้องใช้ install script** — เมื่อมี Bun แล้วสคริปต์แค่ทำสิ่งนี้:

```powershell
& "$env:USERPROFILE\.bun\bin\bun.exe" install -g "@oh-my-pi/pi-coding-agent"
```

ใช้เวลา ~6 นาที (135 packages) ได้ `omp.exe` ใน `~/.bun/bin`
จะมี postinstall ถูกบล็อก 2 ตัว (`onnxruntime-node`, `protobufjs`) — **ปล่อยไว้** ไม่เกี่ยวกับ bridge

ตรวจ: `omp --version` → ควรได้ `omp/18.x`

---

## ขั้นที่ 3 — Claude login (มนุษย์ต้องทำเอง)

```bash
claude login
```

Agent ทำแทนไม่ได้ ต้องเปิด browser ยืนยันตัวตน ตรวจด้วย `claude whoami`

---

## ขั้นที่ 4 — Bridge extension

ใช้ fork ของ DevVig (target `@oh-my-pi/*` ตรงกับ OMP 18.x):

```bash
mkdir -p ~/.omp/extensions
cd ~/.omp/extensions
git clone --depth 1 https://github.com/DevVig/omp-claude-bridge.git omp-claude-bridge-devvig
cd omp-claude-bridge-devvig
~/.bun/bin/bun.exe install --production
```

### แพตช์เพิ่ม claude-opus-5

fork นี้อัปเดตล่าสุด ก.ค. 2026 ยังไม่รู้จัก opus-5 (OMP รู้จักแล้ว ขาดแค่ mapping) แก้ `src/models.ts` 4 จุด:

**1)** เพิ่ม `"claude-opus-5"` เป็นตัวแรกของ `MODEL_IDS_IN_ORDER`

**2)** ใน `resolveAutoRuntimeModel` เพิ่มก่อน `case "claude-opus-4-8"`:
```ts
case "claude-opus-5":
    return { cliModelId: "claude-opus-5[1m]", contextWindow: ONE_M_CONTEXT };
```

**3)** ใน `resolveForcedOneMRuntimeModel` เพิ่ม case เดียวกัน

**4)** ใน `resolveForcedTwoHundredKRuntimeModel` เพิ่ม:
```ts
case "claude-opus-5":
    return { cliModelId: "claude-opus-5", contextWindow: TWO_HUNDRED_K_CONTEXT };
```

> ⚠️ แพตช์นี้หายถ้า `git pull` — ควร commit ไว้ใน local branch

---

## ขั้นที่ 5 — ไฟล์ config หลัก

**`~/.omp/agent/config.yml`**
```yaml
extensions:
  - C:/Users/ASUS/.omp/extensions/omp-claude-bridge-devvig
defaultThinkingLevel: high
modelRoles:
  default: claude-bridge/claude-opus-5
  plan: claude-bridge/claude-opus-5
  slow: claude-bridge/claude-opus-5
  smol: claude-bridge/claude-haiku-4-5
  commit: claude-bridge/claude-haiku-4-5
task:
  isolation:
    enabled: true
  enableLsp: true
lsp:
  diagnosticsOnEdit: true
github:
  enabled: true
shellPath: C:\Users\ASUS\AppData\Local\hermes\git\bin\bash.exe
memory:
  backend: mnemopi
mnemopi:
  noEmbeddings: true
  scoping: per-project
tools:
  xdev: false
```

> `shellPath` → ปรับเป็น path ของ Git Bash ในเครื่อง (`(Get-Command git).Source` แล้วเปลี่ยน `cmd\git.exe` เป็น `bin\bash.exe`)
> `tools.xdev: false` **สำคัญ** — ไม่งั้น `lsp`/`debug` จะซ่อนอยู่หลัง tool discovery ที่ไม่เสถียร
> `mnemopi.noEmbeddings: true` เลี่ยง onnxruntime ที่ postinstall ถูกบล็อก

**`~/.omp/agent/claude-bridge.json`**
```json
{
  "provider": {
    "plan": "pro",
    "longContextExtraUsage": false,
    "pathToClaudeCodeExecutable": "C:/Users/ASUS/.local/bin/claude.exe"
  },
  "askClaude": { "enabled": false }
}
```
> `plan` → ใส่ `"max"` ถ้าใช้แผน Max | path จาก `(Get-Command claude).Source`

**ตรวจ:** `omp models claude-bridge` ควรเห็น 14 โมเดล รวม `claude-opus-5`
**ทดสอบ:** `omp -p "Reply with exactly: OK"`

---

## ขั้นที่ 6 — Kotlin LSP

**ใช้ตัว community ของ fwcd** (ตัว JetBrains หมดอายุ):

```bash
mkdir -p ~/.omp/tools && cd ~/.omp/tools
curl -sSL -o kls.zip https://github.com/fwcd/kotlin-language-server/releases/download/1.3.13/server.zip
```
```powershell
Expand-Archive "$env:USERPROFILE\.omp\tools\kls.zip" -DestinationPath "$env:USERPROFILE\.omp\tools\kls" -Force
Remove-Item "$env:USERPROFILE\.omp\tools\kls.zip"
```

**สร้าง `~/.omp/tools/kotlin-lsp-wrapper.cmd`** (pin JDK โดยไม่แตะ environment ของเครื่อง):
```bat
@echo off
rem Pins the JDK for kotlin-language-server without touching the machine-wide environment.
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
"%~dp0kls\server\bin\kotlin-language-server.bat" %*
```

**`~/.omp/agent/lsp.json`**
```json
{
  "servers": {
    "kotlin-lsp": { "disabled": true },
    "kotlin-language-server": {
      "command": "C:/Users/ASUS/.omp/tools/kotlin-lsp-wrapper.cmd",
      "args": [],
      "fileTypes": [".kt", ".kts"],
      "languageId": "kotlin",
      "rootMarkers": ["build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle", "pom.xml"],
      "disabled": false
    }
  }
}
```

> การเรียกครั้งแรกต่อไฟล์ใช้เวลา index นาน — ต้องให้ timeout **≥ 180 วินาที** (20 วินาทีไม่พอ)

---

## ขั้นที่ 7 — DAP debugger (ส่วนที่ยากที่สุด)

```bash
cd ~/.omp/tools
curl -sSL -o kda.zip https://github.com/fwcd/kotlin-debug-adapter/releases/download/0.4.4/adapter.zip
```
```powershell
Expand-Archive "$env:USERPROFILE\.omp\tools\kda.zip" -DestinationPath "$env:USERPROFILE\.omp\tools\kda" -Force
Remove-Item "$env:USERPROFILE\.omp\tools\kda.zip"
```

### ทำไมต้องมี shim

OMP กับ adapter ของ fwcd เข้ากันไม่ได้ 2 จุด:

1. **`projectRoot` หาย** — KDA ปฏิเสธ attach ที่ไม่มี argument นี้ แต่ OMP ส่ง `cwd` มาแทน
2. **`configurationDone` ไม่มี response** — KDA ถือเป็นสัญญาณทางเดียวและ**ไม่เคยตอบกลับ** แต่ OMP `await` response นั้น ผลคือ **attach สำเร็จไปแล้วแต่ OMP timeout**

จุดที่ 2 หลอกมาก: log จะแสดง `response attach True` กลับไปถึง OMP เรียบร้อย แต่ session ไม่เกิด

**สร้าง `~/.omp/tools/dap_shim.py`:**

```python
"""
DAP shim for fwcd/kotlin-debug-adapter.

The adapter parks on attach until it receives a DAP `configurationDone`, and it
hard-rejects an attach that carries no `projectRoot`. OMP's debug tool supplies
neither, so an attach hangs until the caller's timeout. This shim sits between
the two, forwards traffic verbatim, and repairs just those two things:

  - fills in `projectRoot` on attach/launch from PROJECT_ROOT or cwd
  - injects a `configurationDone` request once attach/launch is forwarded,
    swallowing its response so the client never sees the synthetic exchange

Everything else passes through untouched.
"""

import json
import os
import subprocess
import sys
import threading

ADAPTER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "kda", "adapter", "bin", "kotlin-debug-adapter.bat")
SYNTHETIC_SEQ = 990001

LOG = (
    open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "dap_shim.log"), "a", buffering=1)
    if os.environ.get("DAP_SHIM_LOG")
    else None
)


def log(*parts):
    if LOG:
        LOG.write(" ".join(str(p) for p in parts) + "\n")


log("=== shim start ===", "argv:", sys.argv, "cwd:", os.getcwd())

proc = subprocess.Popen(
    ADAPTER,
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=sys.stderr,
    shell=True,
    env={**os.environ, "JAVA_HOME": os.environ.get("DAP_JAVA_HOME", r"C:\Program Files\Android\Android Studio\jbr")},
)

lock = threading.Lock()


def frame(msg):
    body = json.dumps(msg).encode("utf-8")
    return b"Content-Length: %d\r\n\r\n%s" % (len(body), body)


def read_message(stream):
    length = None
    while True:
        line = stream.readline()
        if not line:
            return None
        line = line.strip()
        if not line:
            if length is not None:
                break
            continue
        if line.lower().startswith(b"content-length:"):
            length = int(line.split(b":")[1])
    return json.loads(stream.read(length).decode("utf-8"))


def send_to_adapter(msg):
    with lock:
        proc.stdin.write(frame(msg))
        proc.stdin.flush()


def send_to_client(msg):
    out = sys.stdout.buffer
    out.write(frame(msg))
    out.flush()


def client_to_adapter():
    stdin = sys.stdin.buffer
    state = {"timer": None, "injected": False}

    def inject():
        state["injected"] = True
        send_to_adapter({
            "seq": SYNTHETIC_SEQ,
            "type": "request",
            "command": "configurationDone",
            "arguments": {},
        })

    while True:
        msg = read_message(stdin)
        if msg is None:
            break

        command = msg.get("command")
        is_request = msg.get("type") == "request"
        log(">>>", msg.get("type"), command)

        if is_request and command == "configurationDone":
            # KDA treats configurationDone as a one-way signal and never sends a
            # response, so a client that waits for one hangs even though the
            # attach itself succeeded. Deliver it once, then answer on the
            # adapter's behalf.
            if not state["injected"]:
                if state["timer"]:
                    state["timer"].cancel()
                state["injected"] = True
                send_to_adapter(msg)
            send_to_client({
                "type": "response",
                "seq": 0,
                "request_seq": msg.get("seq"),
                "command": "configurationDone",
                "success": True,
            })
            continue

        if is_request and command in ("attach", "launch"):
            args = msg.setdefault("arguments", {})
            if not args.get("projectRoot"):
                args["projectRoot"] = os.environ.get("PROJECT_ROOT") or args.get("cwd") or os.getcwd()
            if command == "attach" and not args.get("hostName"):
                args["hostName"] = args.get("host", "localhost")
            send_to_adapter(msg)
            state["timer"] = threading.Timer(1.0, inject)
            state["timer"].start()
            continue

        send_to_adapter(msg)

    try:
        proc.stdin.close()
    except OSError:
        pass


def adapter_to_client():
    stdout = sys.stdout.buffer
    while True:
        msg = read_message(proc.stdout)
        if msg is None:
            break
        if msg.get("type") == "response" and msg.get("request_seq") == SYNTHETIC_SEQ:
            log("<<< (swallowed synthetic)", msg.get("command"), msg.get("success"))
            continue
        log("<<<", msg.get("type"), msg.get("command") or msg.get("event"), msg.get("success", ""))
        stdout.write(frame(msg))
        stdout.flush()


t = threading.Thread(target=adapter_to_client, daemon=True)
t.start()
client_to_adapter()
t.join(timeout=5)
proc.terminate()
```

**`~/.omp/agent/dap.json`** — เรียก python ตรงๆ **อย่าใช้ `.cmd`** (Windows resolve ไม่ผ่าน):
```json
{
  "adapters": {
    "kotlin-debug-adapter": {
      "command": "C:/Python314/python.exe",
      "args": ["C:/Users/ASUS/.omp/tools/dap_shim.py"],
      "languages": ["kotlin"],
      "fileTypes": [".kt", ".kts"],
      "rootMarkers": ["build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle", "pom.xml"],
      "launchDefaults": { "request": "launch", "mainClass": "", "projectRoot": "" },
      "attachDefaults": {
        "request": "attach",
        "hostName": "localhost",
        "port": 5005,
        "timeout": 20000
      }
    }
  }
}
```

ระดับโปรเจกต์ (ทับค่า global) — `<project>/.omp/dap.json` ใส่ `"projectRoot": "<absolute path>"` เพิ่มใน `attachDefaults`

**ข้อจำกัดที่ต้องรู้:**
- breakpoint ทำงานเฉพาะไฟล์ `.kt` — ไฟล์ `.java` จะตอบ `verified: true` แบบหลอกแล้วไม่เคยหยุด ให้ใช้ `jdb` แทน
- ถ้า adapter JVM ค้าง มันจะถือ JDWP socket ไว้ ทำให้ attach ครั้งต่อไปขึ้น "Connection refused" — เช็ค `netstat -ano | findstr 5005` ว่ากลับเป็น LISTENING
- debug ตั้ง `DAP_SHIM_LOG=1` เพื่อดู message flow ที่ `~/.omp/tools/dap_shim.log`

---

## ขั้นที่ 8 — กฎที่ใช้ทุก session

⚠️ **`RULES.md` ไม่ทำงาน** — bridge อ่านเฉพาะ `AGENTS.md` แล้วเปลี่ยนชื่อเป็น `# CLAUDE.md` ก่อนส่งให้ Claude

และ bridge **เขียนทับข้อความ**: `omp` → `environment`, `.omp` → `.claude` ดังนั้น**ห้ามใส่ path จริงของ OMP** ให้เขียนเชิงพฤติกรรมแทน

`AGENTS.md` เป็นแบบ **either/or ไม่ merge** — ถ้าโปรเจกต์มีของตัวเอง ตัว global จะถูกข้ามทั้งไฟล์

**สร้าง `~/.omp/agent/AGENTS.md`** (ใช้กับโปรเจกต์ที่ไม่มีไฟล์ของตัวเอง) และถ้าโปรเจกต์ไหนมี `AGENTS.md` อยู่แล้ว ให้เพิ่มหัวข้อนี้เข้าไป:

```markdown
## Terminal Harness Tooling

These apply only when the `lsp`, `debug`, or `mcp__*` tools are present in your
toolset. Ignore this section if they are not.

**Language server.** The Kotlin server indexes on the first call against a file.
Allow at least 180s on that first call, then normal timeouts. A 20s default expires
during indexing — retry with the longer budget rather than reporting the server broken.

**Debugging.** The `debug` tool reaches its Kotlin adapter through a shim declared in
`dap.json`. If an attach hangs, confirm `dap.json` still routes through that shim;
pointing it straight at the adapter binary reintroduces a deadlock where the attach
succeeds but the caller never hears back.

Breakpoints resolve only in `.kt` sources. In a `.java` file the adapter reports
`verified: true` and then never stops — use `jdb` for Java instead.

Before re-attaching, confirm the debuggee's JDWP port is LISTENING again. An orphaned
adapter JVM holds the socket and later attaches fail with "Connection refused".

**MCP tools.** MCP servers are discovered from the nearest `.mcp.json` at or above the
directory the session started in, so the full device-control set is only present when
the session starts at the repository root. Started from a module directory, most are
missing. If an expected `mcp__*` tool is absent, say the session may have started below
the config rather than reporting the tool unavailable.
```

เพิ่ม `.omp/` ลง `.gitignore` (แบบไม่ผูกกับราก เพราะไฟล์อยู่ในโฟลเดอร์ย่อย)

---

## ขั้นที่ 9 — MCP

**ไม่ต้องติดตั้งอะไรเพิ่ม** OMP อ่าน `.mcp.json` เองโดยตรง (bridge ปิดกั้น MCP ของ Claude Code ไว้โดยเจตนา เพราะ OMP เป็นคนรันเครื่องมือเอง ไม่ใช่ Claude)

⚠️ **ขึ้นกับ cwd** — MCP servers ถูกค้นจาก `.mcp.json` ที่ใกล้ที่สุดขึ้นไป ถ้าเปิด session ในโฟลเดอร์ย่อยจะเห็น tool ไม่ครบ

---

## ขั้นที่ 10 — ตรวจรับงาน

```powershell
omp models claude-bridge                    # ต้องเห็น 14 โมเดล รวม claude-opus-5
omp -p "Reply with exactly: OK"             # → OK
omp -p "Run: git status"                    # เครื่องมือ bash ทำงาน
omp config get modelRoles                   # default = claude-bridge/claude-opus-5
omp config get defaultThinkingLevel          # high
```

**ทดสอบ LSP** (ในโปรเจกต์ Kotlin ให้ timeout ยาว):
```
omp -p "Use the lsp tool, action=symbols on <ไฟล์ .kt>, timeout 180. Report symbol count."
```

**ทดสอบ DAP** — เปิด JVM ที่มี JDWP ก่อน:
```bash
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -cp <dir> <MainClass>
```
```
omp -p "Use the debug tool: attach adapter=kotlin-debug-adapter port=5005, then threads."
```
ควรได้รายชื่อ thread กลับมา ถ้าค้าง → ตรวจว่า `dap.json` ยังชี้ผ่าน `dap_shim.py`

**ทดสอบกฎ:**
```
omp -p "Without using tools: what timeout for the first lsp call on a Kotlin file?"
```
ต้องตอบ 180s

---

## สิ่งที่ยังไม่เคยพิสูจน์

- **breakpoint ในไฟล์ `.kt`** — ทดสอบได้แค่ `.java` (ซึ่งไม่ทำงานโดยธรรมชาติของ KDA) ยังไม่มี Kotlin debuggee ให้ลอง
- **attach เข้าแอป Android จริง** ผ่าน `adb forward tcp:5005 jdwp:<pid>` — ยังไม่เคยทดสอบเพราะไม่มีอุปกรณ์

สองข้อนี้คือสิ่งที่ควรทดสอบเป็นอันดับแรกเมื่อมีอุปกรณ์

---

## สรุปพื้นที่ดิสก์

| รายการ | ขนาด |
|---|---|
| OMP + deps | ~400 MB |
| bridge fork + deps | ~150 MB |
| kotlin-language-server | 90 MB |
| kotlin-debug-adapter | 9.5 MB |

**อย่าเก็บ** ไฟล์ zip หลังแตกแล้ว และ**อย่าเก็บ** kotlin-lsp ของ JetBrains (1.2 GB และหมดอายุใช้ไม่ได้)
