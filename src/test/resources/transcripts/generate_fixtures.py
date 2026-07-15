#!/usr/bin/env python3
"""Generate schema-faithful, secret-free Claude JSONL transcript fixtures.

These reproduce the real ~/.claude/projects/<escaped>/<uuid>.jsonl schema
(Claude Code 2.1.x) observed during the Group 0 spike, without any real user
content. Every string is synthetic. Run:  python3 generate_fixtures.py

Record types observed in the wild (see Group 0 findings):
  user | assistant | system | attachment | mode | permission-mode |
  ai-title | last-prompt | pr-link | file-history-snapshot
Assistant content blocks: text | thinking | tool_use
User array content blocks: text | tool_result | image
tool_result.content: string OR array of {text|image|tool_reference}
system subtypes: compact_boundary | local_command | turn_duration | away_summary
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))

# A valid 1x1 transparent PNG (safe, tiny) — the pasted-image case.
PNG_1X1 = ("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNk"
           "+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")

VERSION = "2.1.210"
CWD = "/home/dev/project"
BRANCH = "main"


def base(uuid, parent, rtype, **extra):
    r = {
        "parentUuid": parent,
        "isSidechain": False,
        "userType": "external",
        "cwd": CWD,
        "sessionId": SESSION,
        "version": VERSION,
        "gitBranch": BRANCH,
        "type": rtype,
        "uuid": uuid,
        "timestamp": extra.pop("ts", "2026-07-15T12:00:00.000Z"),
    }
    r.update(extra)
    return r


def user_text(uuid, parent, text, ts):
    r = base(uuid, parent, "user", ts=ts, entrypoint="cli",
             promptId=f"prompt-{uuid[:8]}", promptSource="typed",
             permissionMode="default", origin={"kind": "human"})
    r["message"] = {"role": "user", "content": text}
    return r


def user_blocks(uuid, parent, blocks, ts, src_tool_uuid=None):
    r = base(uuid, parent, "user", ts=ts, entrypoint="cli")
    if src_tool_uuid:
        r["sourceToolAssistantUUID"] = src_tool_uuid
    r["message"] = {"role": "user", "content": blocks}
    return r


def assistant(uuid, parent, blocks, ts, model="claude-opus-4-8",
              stop_reason="end_turn"):
    r = base(uuid, parent, "assistant", ts=ts, entrypoint="cli",
             requestId=f"req-{uuid[:8]}")
    r["message"] = {
        "model": model,
        "id": f"msg_{uuid[:12]}",
        "type": "message",
        "role": "assistant",
        "content": blocks,
        "stop_reason": stop_reason,
        "stop_sequence": None,
        "stop_details": None,
        "usage": {"input_tokens": 100, "output_tokens": 50,
                  "cache_read_input_tokens": 0,
                  "cache_creation_input_tokens": 0, "service_tier": "standard"},
        "diagnostics": None,
    }
    return r


def system(uuid, parent, subtype, content, ts, **extra):
    r = base(uuid, parent, "system", ts=ts, entrypoint="cli",
             subtype=subtype, isMeta=True, level="info", content=content)
    r.update(extra)
    return r


def turn_duration(uuid, parent, ms, ts, count=1):
    r = base(uuid, parent, "system", ts=ts, entrypoint="cli",
             subtype="turn_duration", isMeta=True, durationMs=ms,
             messageCount=count)
    return r


# --- Content-block builders -------------------------------------------------
def t(text):
    return {"type": "text", "text": text}


def think(text):
    return {"type": "thinking", "thinking": text,
            "signature": "sig-" + "0" * 8}


def tool_use(tid, name, inp):
    return {"type": "tool_use", "id": tid, "name": name, "input": inp,
            "caller": "assistant"}


def tool_result(tid, content, is_error=False):
    return {"type": "tool_result", "tool_use_id": tid, "content": content,
            "is_error": is_error}


def image_b64(data=PNG_1X1, media="image/png"):
    return {"type": "image",
            "source": {"type": "base64", "media_type": media, "data": data}}


def tool_reference(name):
    return {"type": "tool_reference", "tool_name": name}


def write_jsonl(name, records):
    path = os.path.join(HERE, name)
    with open(path, "w") as f:
        for r in records:
            f.write(json.dumps(r, separators=(",", ":")) + "\n")
    print(f"wrote {name}: {len(records)} records")


# ---------------------------------------------------------------------------
# Fixture 1: short-session — the canonical parser fixture.
# Exercises: user(string), assistant(thinking+text+tool_use),
# user(tool_result string), assistant(text with inline+block math),
# tool_result with ARRAY content (text + image + tool_reference),
# an is_error tool_result, a pasted image block, and internal records.
# ---------------------------------------------------------------------------
SESSION = "00000000-0000-4000-8000-000000000001"
short = []
short.append(user_text("u1", None, "Solve the integral of x^2 from 0 to 1.",
                        "2026-07-15T12:00:00.000Z"))
short.append(assistant("a1", "u1", [
    think("The user wants a definite integral. I'll read the notes file first."),
    t("Let me check your notes."),
    tool_use("toolu_01aaa", "Read", {"file_path": "/home/dev/project/notes.md"}),
], "2026-07-15T12:00:01.000Z", stop_reason="tool_use"))
short.append(user_blocks("u2", "a1",
             [tool_result("toolu_01aaa", "# Notes\nCompute integral.")],
             "2026-07-15T12:00:02.000Z", src_tool_uuid="a1"))
short.append(assistant("a2", "u2", [
    t("The definite integral is inline $\\int_0^1 x^2\\,dx$ which evaluates to:\n\n"
      "$$\\int_0^1 x^2\\,dx = \\frac{1}{3}$$\n\n"
      "Here is a code block with a dollar sign that is NOT math:\n\n"
      "```bash\necho \"it cost $5 and $10\"\n```\n"),
], "2026-07-15T12:00:03.000Z"))
short.append(turn_duration("s1", "a2", 3200, "2026-07-15T12:00:03.100Z", 3))
# A pasted image from the user, plus a follow-up with an array tool_result.
short.append(user_blocks("u3", "s1",
             [t("Here is a screenshot:"), image_b64()],
             "2026-07-15T12:00:10.000Z"))
short.append(assistant("a3", "u3", [
    t("I see the image. Running a check."),
    tool_use("toolu_01bbb", "Bash", {"command": "ls", "description": "list"}),
], "2026-07-15T12:00:11.000Z", stop_reason="tool_use"))
# tool_result with ARRAY content: text + image + tool_reference (observed shape)
short.append(user_blocks("u4", "a3",
             [tool_result("toolu_01bbb",
                          [t("output line"), image_b64(),
                           tool_reference("Read")])],
             "2026-07-15T12:00:12.000Z", src_tool_uuid="a3"))
# A failing tool call (is_error true)
short.append(assistant("a4", "u4", [
    tool_use("toolu_01ccc", "Bash", {"command": "false", "description": "fail"}),
], "2026-07-15T12:00:13.000Z", stop_reason="tool_use"))
short.append(user_blocks("u5", "a4",
             [tool_result("toolu_01ccc", "command failed: exit 1",
                          is_error=True)],
             "2026-07-15T12:00:14.000Z", src_tool_uuid="a4"))
short.append(assistant("a5", "u5",
             [t("That command failed, as expected.")],
             "2026-07-15T12:00:15.000Z"))
write_jsonl("short-session.jsonl", short)

# ---------------------------------------------------------------------------
# Fixture 2: compact-boundary — a /compact epoch marker mid-session.
# ---------------------------------------------------------------------------
SESSION = "00000000-0000-4000-8000-000000000002"
comp = []
comp.append(user_text("cu1", None, "First question before compaction.",
                       "2026-07-15T13:00:00.000Z"))
comp.append(assistant("ca1", "cu1", [t("First answer.")],
                      "2026-07-15T13:00:01.000Z"))
comp.append(system("cs-local", "ca1", "local_command",
                   "<command-name>/compact</command-name>",
                   "2026-07-15T13:05:00.000Z"))
comp.append(system("cs-boundary", "cs-local", "compact_boundary",
                   "Conversation compacted.", "2026-07-15T13:05:01.000Z",
                   compactMetadata={"trigger": "manual",
                                    "preTokens": 120000},
                   logicalParentUuid=None))
comp.append(user_text("cu2", "cs-boundary", "Question after compaction.",
                       "2026-07-15T13:06:00.000Z"))
comp.append(assistant("ca2", "cu2", [t("Answer after compaction.")],
                      "2026-07-15T13:06:01.000Z"))
write_jsonl("compact-boundary.jsonl", comp)

# ---------------------------------------------------------------------------
# Fixture 3: unknown-blocks — schema-drift tolerance. Contains a
# tool_reference at top level of an assistant turn (unusual), a fabricated
# FUTURE block type, and a fabricated FUTURE record type. Parser must
# preserve these as UnknownBlock / unknown record, never crash or drop.
# ---------------------------------------------------------------------------
SESSION = "00000000-0000-4000-8000-000000000003"
unk = []
unk.append(user_text("xu1", None, "Trigger some unusual content.",
                      "2026-07-15T14:00:00.000Z"))
unk.append(assistant("xa1", "xu1", [
    t("Normal text."),
    {"type": "future_block_type_v99", "payload": {"foo": "bar"}},
    tool_reference("SomeFutureTool"),
], "2026-07-15T14:00:01.000Z"))
# A fabricated future top-level record type.
unk.append(base("xr1", "xa1", "future_record_type",
                ts="2026-07-15T14:00:02.000Z", entrypoint="cli",
                somefield="somevalue"))
unk.append(assistant("xa2", "xr1", [t("Recovered fine.")],
                     "2026-07-15T14:00:03.000Z"))
# A partial trailing line is appended by the test itself, not here.
write_jsonl("unknown-blocks.jsonl", unk)

# ---------------------------------------------------------------------------
# Fixture 4: large-session — windowing/paging + memory bound (R14).
# Many alternating turns; deterministic; includes periodic large tool output.
# ---------------------------------------------------------------------------
SESSION = "00000000-0000-4000-8000-000000000004"
large = []
prev = None
N = 400
for i in range(N):
    uid = f"lu{i}"
    large.append(user_text(uid, prev, f"Turn {i}: please continue step {i}.",
                           f"2026-07-15T15:{(i // 60) % 60:02d}:{i % 60:02d}.000Z"))
    aid = f"la{i}"
    blocks = [t(f"Response for turn {i}. Value = {i * i}.")]
    if i % 25 == 0:
        # periodic large tool output — the "show full" truncation case
        tid = f"toolu_large_{i}"
        blocks = [tool_use(tid, "Bash", {"command": f"seq {i}"})]
        large.append(assistant(aid, uid, blocks,
                     f"2026-07-15T15:{(i // 60) % 60:02d}:{i % 60:02d}.500Z",
                     stop_reason="tool_use"))
        big = "\n".join(f"line {j}" for j in range(500))
        large.append(user_blocks(f"lr{i}", aid,
                     [tool_result(tid, big)],
                     f"2026-07-15T15:{(i // 60) % 60:02d}:{i % 60:02d}.700Z",
                     src_tool_uuid=aid))
        prev = f"lr{i}"
    else:
        large.append(assistant(aid, uid, blocks,
                     f"2026-07-15T15:{(i // 60) % 60:02d}:{i % 60:02d}.500Z"))
        prev = aid
write_jsonl("large-session.jsonl", large)

print("done")
