# Atlas roadmap

Big ideas agreed on, in the order we plan to build them.

## 1. App agent — "Atlas does it for you" (agreed, next up)

Say one sentence; Atlas operates your other apps to get it done: WhatsApp, Foodpanda, Careem, Daraz, YouTube, Chrome, JazzCash.

- **How:** the accessibility service reads every button and field on screen and can tap, type and scroll. At each step the AI sees the screen and picks the next action; a screenshot is added when the screen is unclear.
- **Safety:** always stops for confirmation before payments, messages to new people, or deletions. A floating bubble shows progress, and one tap stops it.
- **Learns:** a task that succeeds is saved as a recipe and replayed in seconds, with little AI, next time. When an app redesigns its screens, the recipe re-learns itself.
- **Limits:** banking apps that block screen reading get guidance instead of taps. The first run of a new task is slower than a person.

Build in stages, each released:
1. Reliable in WhatsApp, YouTube, Chrome, Careem and Foodpanda.
2. Saved recipes with fast replay.
3. Scheduled and voice-triggered tasks from anywhere ("every 1st, pay the internet bill").

## 2. Total recall (candidate)

Atlas privately indexes what passes through the phone (messages, notifications, pages read, screens), encrypted on the device, and answers questions like "what was the shop number my cousin sent last week?". This also feeds the app agent's memory.

## Also agreed

- Make the existing expense tracker robust: bank, JazzCash and Easypaisa SMS parsing, duplicate detection, categories, budget alerts.
- Not doing: smart-home control.
