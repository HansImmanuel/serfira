# Triage Labels

The skills speak in terms of five canonical triage roles. This file maps those roles to the actual label strings used in this repo's issue tracker.

| Label in mattpocock/skills | Label in our tracker | Meaning                                  |
| -------------------------- | -------------------- | ---------------------------------------- |
| `needs-triage`             | `needs-triage`       | Maintainer needs to evaluate this issue  |
| `needs-info`               | `needs-info`         | Waiting on reporter for more information |
| `ready-for-agent`          | `ready-for-agent`    | Fully specified, ready for an AFK agent  |
| `ready-for-human`          | `ready-for-human`    | Requires human implementation            |
| `wontfix`                  | `wontfix`            | Will not be actioned                     |

When a skill mentions a role (e.g. "apply the AFK-ready triage label"), use the corresponding label string from this table.

Edit the right-hand column to match whatever vocabulary you actually use.

## How labels are recorded in `docs/tasks.md`

The tracker is a markdown file (see `issue-tracker.md`), so a label is a `Triage:` line directly under the task's `Status:` line, e.g. `Triage: ready-for-agent`. `Status` stays the lifecycle; the triage role does not replace it:

| Triage role       | `Status` it pairs with                                              |
| ----------------- | ------------------------------------------------------------------- |
| `needs-triage`    | `TODO`                                                              |
| `needs-info`      | `BLOCKED`, with the open question as an `A-n` row in Planning Notes |
| `ready-for-agent` | `TODO`                                                              |
| `ready-for-human` | `TODO`                                                              |
| `wontfix`         | `DEFERRED`, with the reason                                         |

Remove the `Triage:` line once the task is `IN PROGRESS`.
