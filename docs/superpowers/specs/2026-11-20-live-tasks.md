# Live tasks

## Goal

Show, inside the app, agent work that keeps running after the user leaves a chat. Today that work is only visible on the lock screen (LiveNotify) or by opening Recents and filtering "Working".

## What the user sees

A compact pill near the bottom of the screen whenever a top-level session is working:

- One task: spinner + current step ("Editing Auth.kt").
- Several tasks: "2 tasks · Editing Auth.kt".
- A permission or question waiting: warning icon, accent color, "Needs your OK".
- Nothing running: no pill at all.

Tap the pill: a bottom sheet lists each active session.

- Row: chat title, current step (or retry reason, or "Waiting for your answer"), elapsed time.
- Waiting-for-input rows come first.
- Tap a row: close the sheet, open that chat.
- Read-only. No stop button; the chat already has one.

The pill sits above the composer on Home and Chat, and above the navigation bar on other screens. On a chat's own screen, that chat is left out of the pill and the sheet: its steps and Stop are already on screen. If another chat is also working, the pill shows that one.

## Data

No new API and no polling.

- `ChatHub.statuses` / `retries`: which sessions are busy or retrying, plus the retry reason.
- `ChatHub.permissions` / `questions`: which sessions wait for the user.
- `LiveNotify`: already parses tool parts for the lock-screen notification. Expose its runs as `StateFlow<List<LiveTask>>` (session id, step, steps, files, started). One source, no duplicate event parsing.
- `ChatHub` sessions: title and directory.

Cold-start gap: if a session is already working when the app opens, there is no step yet. The pill says "Working" until the next tool event. Change the part handler to start tracking a session when `statuses[sid]` is already set.

Hide child sessions (`parentID != null`); their work belongs to the parent row.

If the SSE stream is disconnected, hide the pill. The last known statuses may be stale, and a phantom "working" row is worse than a gap. It returns on reconnect, when the statuses refresh.

## Files

- `runtime` no changes.
- `data/LiveNotify.kt`: expose the activity feed.
- `data/BackgroundTasks.kt`: `TaskRow`, pure `rows(...)` builder and a small summary for the pill.
- `ui/components/BackgroundTasks.kt`: pill and sheet.
- `ui/screens/HomeScreen.kt`, `ui/screens/ChatScreen.kt`: pill above the composer.
- `ui/Root.kt`: pill overlay for every other route.
- `test/.../BackgroundTasksTest.kt`: builder tests.

## Tests

Unit tests for `rows` / summary, no UI tests:

- busy session with a live step
- retry reason becomes the detail
- permission/question beats the step and sorts first
- the open chat is excluded when it is alone
- child sessions are ignored
- nothing active produces an empty list
- several tasks keep a stable order by start time

## Out of scope

- Install, download, server and backup jobs. They already show progress where they run; the same sheet can include them later.
- Notifications (unchanged).
- Task history.
- Stopping a run from the sheet.

## Working branch

`feat/live-tasks`, stacked on `fix/stability-batch-1` so the existing CI build runs. Separate pull request; it does not touch PR #9's commits.
