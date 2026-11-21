# Live Tasks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show running agent sessions across the app in a live pill and a task sheet, so work that continues after the user leaves a chat stays visible.

**Architecture:** A pure `BackgroundTasks` builder turns ChatHub statuses, retry reasons and pending prompts plus LiveNotify's live step feed into `TaskRow`s. A Compose pill observes the builder. Home and Chat place the pill above their composer; every other route gets a bottom overlay.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), kotlinx.coroutines `StateFlow`, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-20-live-tasks.md`

## Global Constraints

- Base branch: `fix/stability-batch-1`. Feature branch: `feat/live-tasks`. Do not add these commits to PR #9.
- No local JDK or Android SDK in this environment. CI (`build.yml`) is the only compiler and test runner. `./gradlew` cannot run here.
- No new dependencies, Android permissions, server endpoints or polling.
- UI text is English and hardcoded, like the rest of the app.
- Keep the temporary `test/device-debug` branch and its `DeviceProbe` out of this branch.
- Commit style: imperative sentence, no AI attribution.

## Review Focus

- SSE stream down: last statuses may be stale. The builder must return no rows when `connected` is false, so no phantom "working" pill survives a crashed server.
- A pending permission or question with no busy status must still produce a row.
- Child sessions (subagents) must not become their own rows; their work belongs to the parent.
- The chat already on screen must not be listed again, but other tasks must remain.
- Cold start: a session already busy before the app opened has no live step yet and must show "Working", then update on the next tool event.
- Long titles and steps must stay on one line and ellipsize; the pill must not grow past the screen.

---

### Task 0: Branch and documents

**Files:**
- Create: `docs/superpowers/specs/2026-11-20-live-tasks.md`
- Create: `docs/superpowers/plans/2026-11-20-live-tasks.md`

**Interfaces:**
- Consumes: nothing.
- Produces: the spec and this plan on the feature branch.

- [ ] **Step 1: Create the branch from the stability branch**

```bash
git switch fix/stability-batch-1
git switch -c feat/live-tasks
mkdir -p docs/superpowers/specs docs/superpowers/plans
```

- [ ] **Step 2: Put the approved spec and this plan in `docs/superpowers/`**

The spec is the approved design document. Correct the disconnected-stream rule to: hide the pill while the event stream is down.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers
git commit -m "Document the live task view"
```

Do not push yet. Task 1 pushes both commits together.

---

### Task 1: Task list builder and tests

**Files:**
- Create: `app/src/main/java/com/celoext/data/BackgroundTasks.kt`
- Test: `app/src/test/java/com/celoext/BackgroundTasksTest.kt`

**Interfaces:**
- Consumes: `Map<String, String>` statuses, `Map<String, RetryInfo>` retries, `List<PermissionReq>` permissions, `List<QuestionReq>` questions, `Map<String, Session>` sessions, `List<LiveTask>` live, `Boolean connected`, `String? excludeID`.
- Produces:
  - `data class LiveTask(val sessionID: String, val step: String, val steps: Int, val files: Int, val started: Long)`
  - `data class TaskRow(val sessionID: String, val title: String, val detail: String, val needsInput: Boolean, val started: Long, val steps: Int, val files: Int)`
  - `object BackgroundTasks { fun rows(statuses, retries, permissions, questions, sessions, live, connected, excludeID): List<TaskRow>; fun pill(rows: List<TaskRow>): String? }`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/celoext/BackgroundTasksTest.kt`:

```kotlin
package com.celoext

import com.celoext.api.PermissionReq
import com.celoext.api.QuestionReq
import com.celoext.api.Session
import com.celoext.api.TimeInfo
import com.celoext.data.BackgroundTasks
import com.celoext.data.LiveTask
import com.celoext.data.RetryInfo
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundTasksTest {
    private fun session(id: String, title: String = "Chat $id", parent: String? = null, created: Long = 10L) =
        Session(id = id, title = title, parentID = parent, time = TimeInfo(created = created))

    private fun live(id: String, step: String = "Editing Auth.kt", started: Long = 100L) =
        LiveTask(id, step, steps = 2, files = 1, started = started)

    private fun perm(id: String, sid: String) =
        PermissionReq(id, sid, "bash", emptyList(), JsonObject(emptyMap()), null)

    private fun question(id: String, sid: String) = QuestionReq(id, sid, emptyList())

    private fun rows(
        statuses: Map<String, String> = emptyMap(),
        retries: Map<String, RetryInfo> = emptyMap(),
        perms: List<PermissionReq> = emptyList(),
        questions: List<QuestionReq> = emptyList(),
        sessions: Map<String, Session> = emptyMap(),
        live: List<LiveTask> = emptyList(),
        connected: Boolean = true,
        exclude: String? = null,
    ) = BackgroundTasks.rows(statuses, retries, perms, questions, sessions, live, connected, exclude)

    @Test fun liveStepBecomesTheDetail() {
        val r = rows(
            statuses = mapOf("ses_a" to "busy"),
            sessions = mapOf("ses_a" to session("ses_a")),
            live = listOf(live("ses_a")),
        )
        assertEquals(1, r.size)
        assertEquals("Chat ses_a", r[0].title)
        assertEquals("Editing Auth.kt", r[0].detail)
        assertEquals(false, r[0].needsInput)
    }

    @Test fun retryReasonBeatsTheLiveStep() {
        val r = rows(
            statuses = mapOf("ses_a" to "retry"),
            retries = mapOf("ses_a" to RetryInfo("Provider overloaded", null, null, null)),
            live = listOf(live("ses_a")),
        )
        assertEquals("Provider overloaded", r[0].detail)
    }

    @Test fun pendingPermissionAppearsAndSortsFirst() {
        val r = rows(
            statuses = mapOf("ses_a" to "busy"),
            perms = listOf(perm("p1", "ses_b")),
            sessions = mapOf("ses_a" to session("ses_a", created = 10), "ses_b" to session("ses_b", created = 20)),
            live = listOf(live("ses_a", started = 20)),
        )
        assertEquals(listOf("ses_b", "ses_a"), r.map { it.sessionID })
        assertTrue(r[0].needsInput)
        assertEquals("Waiting for your answer", r[0].detail)
    }

    @Test fun pendingQuestionAlsoCountsAsWaiting() {
        val r = rows(questions = listOf(question("q1", "ses_c")))
        assertEquals(1, r.size)
        assertTrue(r[0].needsInput)
    }

    @Test fun openChatIsLeftOut() {
        val r = rows(statuses = mapOf("ses_a" to "busy"), live = listOf(live("ses_a")), exclude = "ses_a")
        assertTrue(r.isEmpty())
    }

    @Test fun otherTasksSurviveWhenTheOpenChatIsLeftOut() {
        val r = rows(statuses = mapOf("ses_a" to "busy", "ses_b" to "busy"), exclude = "ses_a")
        assertEquals(listOf("ses_b"), r.map { it.sessionID })
    }

    @Test fun childSessionsAreIgnored() {
        val r = rows(
            statuses = mapOf("ses_child" to "busy"),
            sessions = mapOf("ses_child" to session("ses_child", parent = "ses_parent")),
        )
        assertTrue(r.isEmpty())
    }

    @Test fun unknownSessionsStillGetARow() {
        val r = rows(statuses = mapOf("ses_x" to "busy"))
        assertEquals("Task", r[0].title)
        assertEquals("Working", r[0].detail)
    }

    @Test fun nothingRunningMeansNoPill() {
        val r = rows()
        assertTrue(r.isEmpty())
        assertNull(BackgroundTasks.pill(r))
    }

    @Test fun disconnectedStreamHidesStaleRows() {
        val r = rows(statuses = mapOf("ses_a" to "busy"), live = listOf(live("ses_a")), connected = false)
        assertTrue(r.isEmpty())
    }

    @Test fun pillForOneTaskIsItsStep() {
        val r = rows(statuses = mapOf("ses_a" to "busy"), live = listOf(live("ses_a")))
        assertEquals("Editing Auth.kt", BackgroundTasks.pill(r))
    }

    @Test fun pillForSeveralTasksCountsThem() {
        val r = rows(
            statuses = mapOf("ses_a" to "busy", "ses_b" to "busy"),
            live = listOf(live("ses_a", started = 100), live("ses_b", step = "Reading main.kt", started = 200)),
        )
        assertEquals("2 tasks · Editing Auth.kt", BackgroundTasks.pill(r))
    }

    @Test fun pillForWaitingSaysNeedsYourOkay() {
        val one = rows(perms = listOf(perm("p1", "ses_a")))
        assertEquals("Needs your OK", BackgroundTasks.pill(one))
        val two = rows(
            statuses = mapOf("ses_a" to "busy"),
            perms = listOf(perm("p1", "ses_b")),
            live = listOf(live("ses_a")),
        )
        assertEquals("2 tasks · Needs your OK", BackgroundTasks.pill(two))
    }

    @Test fun earliestTaskComesFirst() {
        val r = rows(
            statuses = mapOf("ses_a" to "busy", "ses_b" to "busy"),
            sessions = mapOf("ses_a" to session("ses_a", created = 50), "ses_b" to session("ses_b", created = 10)),
        )
        assertEquals(listOf("ses_b", "ses_a"), r.map { it.sessionID })
    }
}
```

- [ ] **Step 2: Implement `BackgroundTasks.kt`**

Place `LiveTask`, `TaskRow` and `object BackgroundTasks` in `com.celoext.data`.

`rows` behavior, in order:

1. If `connected` is false, return `emptyList()`.
2. `waiting` = session ids from `permissions` plus `questions`.
3. Candidate ids = `statuses.keys + waiting`, minus `excludeID`.
4. Drop a candidate when its session exists and `parentID != null`.
5. Title = `session?.displayTitle` or `"Task"` when the session is not loaded yet.
6. Detail precedence: waiting → `"Waiting for your answer"`; retry with non-blank message → that message; live step → step; otherwise `"Working"`.
7. `started` = live `started`; else session `time.created` when greater than 0; else `Long.MAX_VALUE`.
8. Sort: `needsInput` first, then `started` ascending, then `sessionID`.

`pill` behavior:

- Empty → `null`.
- One row → `"Needs your OK"` when waiting, else its detail.
- Several with any waiting → `"<n> tasks · Needs your OK"`.
- Several otherwise → `"<n> tasks · " + first row's detail`.

- [ ] **Step 3: Commit and push, then run CI**

```bash
git add app/src/main/java/com/celoext/data/BackgroundTasks.kt app/src/test/java/com/celoext/BackgroundTasksTest.kt docs/superpowers
git commit -m "Build the live task list from statuses and live steps"
git push fork feat/live-tasks
gh run list --repo XKILLER2006Y/opencodee --branch feat/live-tasks --limit 1
```

Expected: unit tests and `assembleDebug` green. This is the first executable check; there is no local JVM. If CI is red, read the failing line with:

```bash
gh run view <run-id> --repo XKILLER2006Y/opencodee --log-failed
```

Fix in the same commit with `git commit --amend --no-edit` and push with `--force-with-lease`.

---

### Task 2: Expose the live step feed

**Files:**
- Modify: `app/src/main/java/com/celoext/data/LiveNotify.kt`

**Interfaces:**
- Consumes: `LiveTask` from Task 1.
- Produces: `val LiveNotify.active: StateFlow<List<LiveTask>>`, sorted by `started`.

- [ ] **Step 1: Add the feed**

After the private `runs` map:

```kotlin
private val _active = MutableStateFlow<List<LiveTask>>(emptyList())
/** What is running now, for the in-app task pill. Empty when no session works. */
val active: StateFlow<List<LiveTask>> = _active.asStateFlow()

private fun publish() {
    _active.value = runs.map { (sid, r) ->
        LiveTask(sid, r.step, r.steps, r.files.size, r.started)
    }.sortedBy { it.started }
}
```

Imports: `kotlinx.coroutines.flow.MutableStateFlow`, `kotlinx.coroutines.flow.StateFlow`, `kotlinx.coroutines.flow.asStateFlow`.

- [ ] **Step 2: Publish on every change**

Call `publish()` once at the end of `handle`, after the `when` block, and in `done` after removing the run and cancelling the notification.

- [ ] **Step 3: Track a session that was already busy at cold start**

In `message.part.updated`, replace the `runs[sid] ?: return` lookup with:

```kotlin
val r = runs[sid] ?: if (Graph.chats.statuses.value[sid] != null) Run().also { runs[sid] = it } else return
```

This gives the first tool event a place to land when the session started before the app opened.

- [ ] **Step 4: Commit and push**

```bash
git add app/src/main/java/com/celoext/data/LiveNotify.kt
git commit -m "Expose the running agent steps to the app"
git push fork feat/live-tasks
gh run list --repo XKILLER2006Y/opencodee --branch feat/live-tasks --limit 1
```

Expected: CI green. Existing notification behavior is unchanged; the only new side effect is the read-only flow.

---

### Task 3: Pill and task sheet

**Files:**
- Create: `app/src/main/java/com/celoext/ui/components/BackgroundTasks.kt`
- Modify: `app/src/main/java/com/celoext/ui/Root.kt` (inside `Shell`, after `ServerBanner`)
- Modify: `app/src/main/java/com/celoext/ui/screens/HomeScreen.kt` (above `ComposerHost`)
- Modify: `app/src/main/java/com/celoext/ui/screens/ChatScreen.kt` (before the question/composer block)

**Interfaces:**
- Consumes: `BackgroundTasks.rows` and `BackgroundTasks.pill` from Task 1; `LiveNotify.active` from Task 2.
- Produces: `@Composable fun LiveTaskPill(nav: Nav, excludeID: String? = null, modifier: Modifier = Modifier)`.

- [ ] **Step 1: Write the composable**

In `com.celoext.ui.components`, collect:

```kotlin
Graph.chats.statuses, retries, permissions, questions, allSessions
LiveNotify.active
Graph.events.connected
```

Build rows with `remember(...) { BackgroundTasks.rows(..., connected = connected, excludeID = excludeID) }`. Return early when `BackgroundTasks.pill(rows) == null`.

Layout:

- Outer `Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center)`, no click.
- Inner `Row`: `clip(CircleShape)`, `background(c.surfaceHigh)`, 1 dp border (`c.warn` at 50% when any row waits, else `c.border`), `clickable { sheet = true }`, padding 14 dp horizontal and 9 dp vertical.
- Icon: `Ico(Ic.Warning, tint = c.warn, size = 16.dp)` when waiting, otherwise `Spinner(14.dp, stroke = 2.dp)`.
- Text: `Txt(pillText, size = 14.sp, color = if (waiting) c.warn else c.text, maxLines = 1)`, 8 dp after the icon. `Txt` ellipsizes when `maxLines` is set.

Close the sheet when the last task ends: `LaunchedEffect(rows.isEmpty()) { if (rows.isEmpty()) sheet = false }`.

Sheet: `OcSheet(onDismiss = { sheet = false }, title = "Tasks")`. Each row is clickable and does `sheet = false; nav.openChat(row.sessionID)`.

- Title, 16 sp, one line.
- Detail, 13 sp, `c.warn` when waiting else `c.textDim`, one line.
- Elapsed on the right, 12 sp: `now` under a minute, `Nm` under an hour, `Nh Mm` otherwise. No elapsed when `started` is `Long.MAX_VALUE` or not positive.
- Spinner 18 dp or warning icon on the left.

- [ ] **Step 2: Place it**

`HomeScreen.kt`: inside the bottom `Column`, before `ComposerHost`:

```kotlin
com.celoext.ui.components.LiveTaskPill(nav, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
```

`ChatScreen.kt`: after `TeamStrip`, before the `if (myQuestion != null || myPerm != null)` block:

```kotlin
com.celoext.ui.components.LiveTaskPill(nav, excludeID = id, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
```

`Root.kt`: in the `Box` that holds `AnimatedContent` and `ServerBanner`, add a separate overlay check after the existing `ServerBanner` line. `ServerBanner` keeps its current route list, including `Route.Code`.

```kotlin
val cur = nav.current
if (cur == Route.Home || cur == Route.NewCode || cur == Route.Code || cur is Route.Chat) ServerBanner(rt)
if (cur != Route.Home && cur != Route.NewCode && cur !is Route.Chat) {
    LiveTaskPill(
        nav,
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
    )
}
```

Do not add `Route.Code` to the pill exclusion list: `CodeScreen` has no composer, so the bottom overlay is safe there.

- [ ] **Step 3: Commit and push**

```bash
git add app/src/main/java/com/celoext/ui/components/BackgroundTasks.kt \
        app/src/main/java/com/celoext/ui/Root.kt \
        app/src/main/java/com/celoext/ui/screens/HomeScreen.kt \
        app/src/main/java/com/celoext/ui/screens/ChatScreen.kt
git commit -m "Show a live task pill above the composer"
git push fork feat/live-tasks
gh run list --repo XKILLER2006Y/opencodee --branch feat/live-tasks --limit 1
```

Expected: unit tests and `assembleDebug` green. `assembleDebug` is the only check that the Compose code compiles.

---

### Task 4: Verify the branch and open the review

**Files:**
- No code changes.

**Interfaces:**
- Consumes: the branch from Tasks 0–3.
- Produces: a green CI run and a draft pull request in the fork.

- [ ] **Step 1: Check for probe leaks and a clean diff**

```bash
grep -rn "DeviceProbe\|oc-install" app/src || echo clean
git diff --check
git rev-list --left-right --count fix/stability-batch-1...HEAD
grep -rh "@Test" app/src/test/java | wc -l
```

Expected: `clean`, no whitespace errors, `0 4` (four commits ahead, none behind), 113 tests (99 existing + 14 new).

- [ ] **Step 2: Confirm the latest CI run is green**

```bash
gh run list --repo XKILLER2006Y/opencodee --branch feat/live-tasks --limit 1 \
  --json databaseId,status,conclusion,headSha
```

Expected: `status=completed`, `conclusion=success`, head SHA equals `git rev-parse HEAD`.

- [ ] **Step 3: Open a draft PR in the fork**

Write the body to `/tmp/opencode/live-tasks-pr.md`, then:

```bash
gh pr create --repo XKILLER2006Y/opencodee --draft \
  --base fix/stability-batch-1 --head feat/live-tasks \
  --title "Live tasks: show background agent work inside the app" \
  --body-file /tmp/opencode/live-tasks-pr.md
```

PR body:

```markdown
Stacks on the stability branch. Shows agent work that continues after you leave a chat.

- Pill with the current step, task count, or "Needs your OK"
- Sheet lists running chats, waiting rows first, tap to open
- Hidden while the event stream is down, so a crashed server leaves no phantom task
- No new API, permissions or dependencies

Tests: 113 unit tests; CI green on <sha>.
```

If GitHub refuses a same-fork pull request, stop and report it. Do not open this against `main`; that would repeat the 17 stability commits.

- [ ] **Step 4: Report**

Send the branch name, CI run id, test count and PR URL. Do not merge.
