# People & Career integration handoff

## Native app wiring

- Register `PeopleCareerFeature.screen` from `app/src/main/java/com/nizam/app/feature/work/PeopleCareerScreen.kt` in the existing Work catalog and native navigation.
- Keep the existing `MeetingScreen` route. This feature updates its review and confirmation flow; it does not replace meeting capture.
- No manifest, Room schema, migration, permission, or signing changes are needed for these screens.

## Storage and record links

- `PeopleCareerStore` stores feature-owned records in an encrypted, atomic local file through Atlas's existing `Sealed` helper.
- New People, application, offer, story, skill-evidence, client, engagement, reimbursement, and meeting-ledger records remain separate from existing records.
- Links use explicit source record IDs. Deleting a feature record does not cascade into existing People, Career, Finance, Documents, Notes, or Tasks data.

## Safety behavior to preserve

- Meeting AI results are editable drafts. Attribution is saved only after an exact transcript-quote check and user confirmation; task creation is a separate action.
- Deleting a meeting ledger leaves its linked Notes entry and any already-created Tasks untouched.
- Offer comparisons show entered assumptions and formulas without ranking or recommending an offer.
- Reimbursement status is manual. Atlas does not submit claims or contact recipients.
- Career and reimbursement exports open an editable preview; redaction or record-selection changes invalidate the old preview before it can be saved.

## Integration regression checklist

- A missing or paraphrased transcript quote cannot confirm an action. Conflicting explicit labels for the same quote leave the speaker unresolved; an owner and deadline are retained only when the quote supports them.
- No task is created during AI extraction or meeting save. Only selected, confirmed actions are added after the separate task button.
- Offer inputs reject negative and non-finite amounts, hours, and commute values.
- Reimbursement allows a positive partial claim, rejects claims above the source expense, and identifies missing receipt records in the export.
- Changing export redaction terms requires rebuilding the preview before saving.
- Deleting a linked feature record leaves the original source record intact.
- Confirm app registration, existing routes, compilation, available tests, and release settings during whole-app integration; this workspace currently has no Java, Gradle executable/wrapper, or Kotlin compiler.

## Feature files

- `app/src/main/java/com/nizam/app/feature/work/PeopleCareerScreen.kt`
- `app/src/main/java/com/nizam/app/feature/work/PeopleCareerStore.kt`
- `app/src/main/java/com/nizam/app/feature/work/PeopleCareerWork.kt`
- `app/src/main/java/com/nizam/app/feature/work/Meeting.kt`