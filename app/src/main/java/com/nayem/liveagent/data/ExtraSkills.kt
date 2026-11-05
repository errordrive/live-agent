package com.nayem.liveagent.data

import kotlinx.serialization.decodeFromString

/**
 * Extra skills shipped with Live Agent v1.1.0.
 *
 * These 35 skills extend the 9 starters in [defaultSkills]; the total is 44.
 * Steps use the tool verbs already used in the codebase:
 * intent_*, a11y_tap, a11y_type, terminal_ubuntu, terminal_termux,
 * terminal_builtin, timer, speak, file_*, verify_media, mcp_call, screen_*.
 * Anything that sends, dials, deletes, or runs a shell command sets
 * needsConfirmation = true.
 */
fun extraSkills(): List<Skill> = listOf(
    // ---- Research ----
    Skill(
        id = "search_web", name = "Search the web",
        description = "Search the web for a question and answer by voice with the best source.",
        triggerExamples = listOf("search the web for X", "google X for me", "look up X"),
        steps = listOf("planner: research with search grounding", "speak: summary", "intent: open best source in Chrome"),
        permissions = listOf("internet"), needsConfirmation = false
    ),
    Skill(
        id = "summarize_page", name = "Summarize a web page",
        description = "Open a web page, read its content, and speak a short summary.",
        triggerExamples = listOf("summarize this page", "what does this article say", "sum up this link"),
        steps = listOf("intent: open URL in Chrome", "planner: read page content", "speak: summary"),
        permissions = listOf("internet"), needsConfirmation = false
    ),
    Skill(
        id = "weather_now", name = "Weather now",
        description = "Fetch the current weather and forecast for a place and speak it.",
        triggerExamples = listOf("what is the weather today", "weather in Dhaka", "will it rain tomorrow"),
        steps = listOf("planner: fetch weather for the requested place", "speak: weather summary"),
        permissions = listOf("internet"), needsConfirmation = false
    ),
    Skill(
        id = "news_briefing", name = "News briefing",
        description = "Fetch the latest headlines on a topic and speak a short briefing.",
        triggerExamples = listOf("what is the news today", "latest news about X", "give me the headlines"),
        steps = listOf("planner: fetch latest headlines", "speak: news briefing"),
        permissions = listOf("internet"), needsConfirmation = false
    ),

    // ---- Notes and files ----
    Skill(
        id = "save_note_file", name = "Save a note to a file",
        description = "Save dictated text as a plain text file in the LiveAgent notes folder.",
        triggerExamples = listOf("save this as a note", "write a note file about X", "save my idea to a file"),
        steps = listOf("file: save text to LiveAgent/notes", "speak: confirm file name and location"),
        permissions = listOf("storage"), needsConfirmation = false
    ),
    Skill(
        id = "read_note", name = "Read a note aloud",
        description = "Find a saved note by title and read its contents aloud.",
        triggerExamples = listOf("read my note about X", "what did I write in my shopping note", "read the note titled X"),
        steps = listOf("file: find note by title in LiveAgent/notes", "file: read note contents", "speak: note contents"),
        permissions = listOf("storage"), needsConfirmation = false
    ),
    Skill(
        id = "list_notes", name = "List my notes",
        description = "List all saved notes by title so the user can pick one.",
        triggerExamples = listOf("list my notes", "what notes do I have", "show my saved notes"),
        steps = listOf("file: list files in LiveAgent/notes", "speak: note titles"),
        permissions = listOf("storage"), needsConfirmation = false
    ),

    // ---- Media, YouTube and music ----
    Skill(
        id = "youtube_search", name = "Search YouTube",
        description = "Search YouTube for a video or song and show the matching results.",
        triggerExamples = listOf("search YouTube for X", "find X on YouTube", "YouTube search for X"),
        steps = listOf("intent: YouTube search with query", "a11y_tap: open matching result", "verify_media: media session is playing"),
        permissions = listOf("accessibility"), needsConfirmation = false
    ),
    Skill(
        id = "play_music", name = "Play music",
        description = "Play a requested song, artist, or playlist in the music app.",
        triggerExamples = listOf("play some music", "play X by Y", "play my workout playlist"),
        steps = listOf("intent: open music app with query", "a11y_tap: tap matching track", "verify_media: media session is playing"),
        permissions = listOf("accessibility"), needsConfirmation = false
    ),
    Skill(
        id = "pause_media", name = "Pause media",
        description = "Pause whatever media is currently playing.",
        triggerExamples = listOf("pause the music", "stop the music", "pause playback"),
        steps = listOf("screen: send media pause key event", "verify_media: media session is paused", "speak: confirm paused"),
        permissions = listOf("accessibility"), needsConfirmation = false
    ),
    Skill(
        id = "take_screenshot", name = "Take a screenshot",
        description = "Capture a screenshot of the current screen and save it to the gallery folder.",
        triggerExamples = listOf("take a screenshot", "screenshot this", "capture the screen"),
        steps = listOf("screen: capture screenshot", "file: save screenshot to Pictures/LiveAgent", "speak: confirm screenshot saved"),
        permissions = listOf("accessibility", "storage"), needsConfirmation = false
    ),

    // ---- Timers and routines ----
    Skill(
        id = "set_timer", name = "Set a timer",
        description = "Set a countdown timer for a given number of minutes and announce when it fires.",
        triggerExamples = listOf("set a timer for 10 minutes", "timer for 5 minutes", "count down 20 minutes"),
        steps = listOf("timer: register countdown for the requested duration", "speak: confirm timer set", "speak: announce when timer fires"),
        permissions = listOf("foreground_service"), needsConfirmation = false
    ),
    Skill(
        id = "set_alarm", name = "Set an alarm",
        description = "Set an alarm for a specific time using the clock app.",
        triggerExamples = listOf("set an alarm for 7 am", "wake me up at 6:30", "alarm at 8 in the morning"),
        steps = listOf("intent: open clock app with alarm time", "a11y_tap: confirm alarm", "speak: confirm alarm set"),
        permissions = listOf("accessibility"), needsConfirmation = false
    ),
    Skill(
        id = "set_reminder", name = "Set a reminder",
        description = "Register a spoken reminder for a later time or after a delay.",
        triggerExamples = listOf("remind me to call mum in an hour", "remind me about X at 5 pm", "remind me tomorrow to buy milk"),
        steps = listOf("timer: register delayed reminder", "speak: confirm reminder", "speak: reminder text when it fires"),
        permissions = listOf("foreground_service"), needsConfirmation = false
    ),

    // ---- Communication (all need confirmation) ----
    Skill(
        id = "make_call", name = "Make a phone call",
        description = "Call a contact by name or a phone number, only after the user confirms.",
        triggerExamples = listOf("call mum", "phone X", "call 01700000000"),
        steps = listOf("confirm: recipient and number", "intent: dial number", "speak: confirm call started"),
        permissions = listOf("phone"), needsConfirmation = true
    ),
    Skill(
        id = "send_sms", name = "Send an SMS",
        description = "Send a text message to a contact, only after the user confirms the text.",
        triggerExamples = listOf("send an SMS to X", "text mum that I am coming", "message X saying hello"),
        steps = listOf("confirm: recipient and message text", "intent: send SMS", "speak: confirm message sent"),
        permissions = listOf("sms"), needsConfirmation = true
    ),
    Skill(
        id = "whatsapp_message", name = "Send a WhatsApp message",
        description = "Open WhatsApp to a contact with a prefilled message, only after confirmation.",
        triggerExamples = listOf("WhatsApp X saying hello", "send a WhatsApp message to mum", "message X on WhatsApp"),
        steps = listOf("confirm: recipient and message text", "intent: open WhatsApp chat with prefilled text", "a11y_tap: send button"),
        permissions = listOf("accessibility"), needsConfirmation = true
    ),
    Skill(
        id = "telegram_message", name = "Send a Telegram message",
        description = "Open Telegram to a contact with a prefilled message, only after confirmation.",
        triggerExamples = listOf("Telegram X saying hello", "send a Telegram message to Y", "message X on Telegram"),
        steps = listOf("confirm: recipient and message text", "intent: open Telegram chat with prefilled text", "a11y_tap: send button"),
        permissions = listOf("accessibility"), needsConfirmation = true
    ),

    // ---- Calendar ----
    Skill(
        id = "create_calendar_event", name = "Create a calendar event",
        description = "Create a calendar event with a title, date, and time.",
        triggerExamples = listOf("add a meeting tomorrow at 3 pm", "create a calendar event for Friday", "schedule lunch with X on Sunday"),
        steps = listOf("intent: open calendar app with event details", "a11y_tap: save event", "speak: confirm event created"),
        permissions = listOf("accessibility"), needsConfirmation = false
    ),
    Skill(
        id = "list_today_events", name = "List today's events",
        description = "Read today's calendar events aloud.",
        triggerExamples = listOf("what is on my calendar today", "what meetings do I have today", "list today's events"),
        steps = listOf("intent: open calendar app for today", "a11y_tap: open today's agenda", "speak: today's events"),
        permissions = listOf("accessibility"), needsConfirmation = false
    ),

    // ---- Settings toggles ----
    Skill(
        id = "toggle_wifi", name = "Toggle Wi-Fi",
        description = "Turn Wi-Fi on or off from the quick settings panel.",
        triggerExamples = listOf("turn Wi-Fi on", "turn off Wi-Fi", "toggle Wi-Fi"),
        steps = listOf("intent: open quick settings", "a11y_tap: Wi-Fi tile", "speak: confirm Wi-Fi state"),
        permissions = listOf("accessibility"), needsConfirmation = false
    ),
    Skill(
        id = "toggle_bluetooth", name = "Toggle Bluetooth",
        description = "Turn Bluetooth on or off from the quick settings panel.",
        triggerExamples = listOf("turn Bluetooth on", "turn off Bluetooth", "toggle Bluetooth"),
        steps = listOf("intent: open quick settings", "a11y_tap: Bluetooth tile", "speak: confirm Bluetooth state"),
        permissions = listOf("accessibility"), needsConfirmation = false
    ),
    Skill(
        id = "toggle_flashlight", name = "Toggle flashlight",
        description = "Turn the flashlight on or off from the quick settings panel.",
        triggerExamples = listOf("turn on the flashlight", "turn off the torch", "toggle flashlight"),
        steps = listOf("intent: open quick settings", "a11y_tap: flashlight tile", "speak: confirm flashlight state"),
        permissions = listOf("accessibility"), needsConfirmation = false
    ),

    // ---- Files and photos ----
    Skill(
        id = "list_photos", name = "List recent photos",
        description = "List the most recent photos in the gallery so the user can pick one to view.",
        triggerExamples = listOf("show my recent photos", "list my photos", "what photos did I take today"),
        steps = listOf("file: list recent images in Pictures", "speak: photo names and dates"),
        permissions = listOf("storage"), needsConfirmation = false
    ),
    Skill(
        id = "delete_file_confirm", name = "Delete a file (with confirmation)",
        description = "Delete a named file, only after the user confirms the exact file.",
        triggerExamples = listOf("delete the file X", "remove this note", "delete the photo from yesterday"),
        steps = listOf("confirm: exact file name and location", "file: delete file", "speak: confirm file deleted"),
        permissions = listOf("storage"), needsConfirmation = true
    ),
    Skill(
        id = "move_file", name = "Move a file",
        description = "Move a file to another folder, only after the user confirms the destination.",
        triggerExamples = listOf("move X to the notes folder", "move this file to Downloads", "move the photo to the backup folder"),
        steps = listOf("confirm: source file and destination folder", "file: move file", "speak: confirm new location"),
        permissions = listOf("storage"), needsConfirmation = true
    ),

    // ---- Notifications ----
    Skill(
        id = "read_notifications", name = "Read notifications",
        description = "Read the current notifications aloud, newest first.",
        triggerExamples = listOf("read my notifications", "what notifications do I have", "any new messages in notifications"),
        steps = listOf("screen: read active notifications", "speak: notification summaries"),
        permissions = listOf("notification_access"), needsConfirmation = false
    ),
    Skill(
        id = "reply_notification", name = "Reply to a notification",
        description = "Reply to a messaging notification, only after the user confirms the reply text.",
        triggerExamples = listOf("reply to that message saying ok", "answer the WhatsApp notification", "reply yes to the last notification"),
        steps = listOf("confirm: reply text", "a11y_tap: open notification reply", "a11y_type: reply text", "a11y_tap: send reply"),
        permissions = listOf("notification_access", "accessibility"), needsConfirmation = true
    ),

    // ---- Terminal and code (all need confirmation) ----
    Skill(
        id = "run_ubuntu_command", name = "Run an Ubuntu command",
        description = "Run a shell command in the Ubuntu proot environment and read the output back.",
        triggerExamples = listOf("run X in Ubuntu", "run this command in Ubuntu", "in Ubuntu run ls"),
        steps = listOf("confirm: command text", "terminal_ubuntu: run command", "terminal_ubuntu: read output", "speak: output summary"),
        permissions = listOf("shell"), needsConfirmation = true
    ),
    Skill(
        id = "run_termux_command", name = "Run a Termux command",
        description = "Run a shell command in Termux and read the output back.",
        triggerExamples = listOf("run X in Termux", "run this in Termux", "in Termux run pwd"),
        steps = listOf("confirm: command text", "terminal_termux: run command", "terminal_termux: read output", "speak: output summary"),
        permissions = listOf("shell"), needsConfirmation = true
    ),
    Skill(
        id = "run_code_snippet", name = "Run a code snippet",
        description = "Write a short code snippet to a file and run it in the built-in terminal, then report the output.",
        triggerExamples = listOf("run this Python code", "run this script", "test this code snippet"),
        steps = listOf("confirm: code and language", "file: write snippet to a temp file", "terminal_builtin: run snippet", "terminal_builtin: read output", "speak: output summary"),
        permissions = listOf("shell"), needsConfirmation = true
    ),

    // ---- Screen control ----
    Skill(
        id = "tap_by_text", name = "Tap by text",
        description = "Tap the on-screen element that shows the given text.",
        triggerExamples = listOf("tap the Settings button", "tap on X", "press the login button"),
        steps = listOf("a11y_tap: find element by text", "a11y_tap: tap element", "verify_media: confirm screen changed if expected"),
        permissions = listOf("accessibility"), needsConfirmation = false
    ),
    Skill(
        id = "scroll_down", name = "Scroll down",
        description = "Scroll the current screen down to reveal more content.",
        triggerExamples = listOf("scroll down", "scroll down a bit", "swipe down to see more"),
        steps = listOf("screen: scroll down", "speak: confirm scrolled"),
        permissions = listOf("accessibility"), needsConfirmation = false
    ),
    Skill(
        id = "scroll_up", name = "Scroll up",
        description = "Scroll the current screen up to go back to earlier content.",
        triggerExamples = listOf("scroll up", "scroll back up", "go to the top"),
        steps = listOf("screen: scroll up", "speak: confirm scrolled"),
        permissions = listOf("accessibility"), needsConfirmation = false
    ),
    Skill(
        id = "press_back", name = "Press back",
        description = "Press the system back button to go to the previous screen.",
        triggerExamples = listOf("go back", "press back", "go to the previous screen"),
        steps = listOf("screen: press back", "speak: confirm went back"),
        permissions = listOf("accessibility"), needsConfirmation = false
    )
)

/**
 * Validates a Skill parsed from user-supplied JSON (add / import flow).
 * Returns Result.success(skill) when the JSON parses and the skill has a
 * valid snake_case id plus a non-blank name and description, otherwise
 * Result.failure with a human-readable reason. The UI integrator calls this
 * before adding an imported skill to [AppStores.saveSkills].
 */
fun validateSkillJson(json: String): Result<Skill> = runCatching {
    val skill = JsonFiles.json.decodeFromString<Skill>(json)
    val idRegex = Regex("^[a-z][a-z0-9_]*$")
    require(skill.id.matches(idRegex)) { "Skill id must be snake_case, e.g. my_skill" }
    require(skill.name.isNotBlank()) { "Skill name must not be blank" }
    require(skill.description.isNotBlank()) { "Skill description must not be blank" }
    require(skill.steps.isNotEmpty()) { "Skill must have at least one step" }
    skill
}
