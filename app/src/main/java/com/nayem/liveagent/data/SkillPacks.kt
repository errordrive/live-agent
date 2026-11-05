package com.nayem.liveagent.data

import kotlinx.serialization.Serializable

@Serializable
data class SkillPack(
    val id: String,
    val name: String,
    val description: String,
    val installCommand: String,
    val requiredEnv: String,
    val guardNote: String,
    val skills: List<Skill> = emptyList()
)

/**
 * Optional skill packs the user can install from the Skills screen.
 * Pack skills are NOT part of [defaultSkills] — they are added to the skill
 * list only when the user installs the pack (the UI integrator's job).
 * No HyperFrames or REA source is copied into this repo; the packs only
 * describe how to install the upstream tools and which terminal commands
 * drive them.
 */
fun skillPacks(): List<SkillPack> = listOf(
    SkillPack(
        id = "hyperframes",
        name = "HyperFrames",
        description = "Render videos from HTML compositions with HyperFrames (https://github.com/heygen-com/hyperframes). Requires Node 24 and ffmpeg in the Ubuntu terminal.",
        installCommand = "npm install -g hyperframes",
        requiredEnv = "Ubuntu terminal with Node 24 and ffmpeg",
        guardNote = "Video rendering runs inside the Ubuntu/Termux environment with Node — it does not run natively on Android.",
        skills = listOf(
            Skill(
                id = "hyperframes_render_video", name = "Render a HyperFrames video",
                description = "Render an MP4 from a HyperFrames composition folder using Node in the Ubuntu terminal.",
                triggerExamples = listOf("render this HyperFrames video", "make a video with HyperFrames", "render the composition in X"),
                steps = listOf(
                    "confirm: composition folder and output file",
                    "terminal_ubuntu: cd to composition folder",
                    "terminal_ubuntu: hyperframes render --out <output.mp4>",
                    "terminal_ubuntu: read render output",
                    "verify_media: output MP4 exists and plays"
                ),
                permissions = listOf("shell"), needsConfirmation = true
            ),
            Skill(
                id = "hyperframes_caption_video", name = "Add captions with HyperFrames",
                description = "Add embedded captions to a video using a HyperFrames composition in the Ubuntu terminal.",
                triggerExamples = listOf("add captions to this video", "caption the video with HyperFrames", "burn subtitles into X"),
                steps = listOf(
                    "confirm: video file and caption text",
                    "terminal_ubuntu: cd to composition folder",
                    "terminal_ubuntu: hyperframes render --out <output.mp4>",
                    "terminal_ubuntu: read render output",
                    "verify_media: output MP4 exists and plays"
                ),
                permissions = listOf("shell"), needsConfirmation = true
            )
        )
    ),
    SkillPack(
        id = "rea",
        name = "REA",
        description = "Reverse-engineer and analyze Android apps with REA (https://github.com/morluto/rea). Requires Node in the Ubuntu terminal.",
        installCommand = "npm install -g rea-agents",
        requiredEnv = "Ubuntu terminal with Node 24",
        guardNote = "Authorized scope only — your own apps, CTF, or authorized targets. Never use on apps you do not own or have permission to test.",
        skills = listOf(
            Skill(
                id = "rea_scan_apk", name = "Scan an APK with REA",
                description = "Scan an APK file with REA and report a summary of its components and findings.",
                triggerExamples = listOf("scan this APK with REA", "analyze the APK file X", "run REA on this app"),
                steps = listOf(
                    "confirm: APK file path",
                    "terminal_ubuntu: rea scan <apk-path>",
                    "terminal_ubuntu: read scan output",
                    "speak: scan summary"
                ),
                permissions = listOf("shell"), needsConfirmation = true
            ),
            Skill(
                id = "rea_analyze_app", name = "Analyze an app with REA",
                description = "Run a deeper REA analysis on an app or project folder and report the key findings.",
                triggerExamples = listOf("analyze this app with REA", "run a deep REA analysis on X", "inspect this app with REA"),
                steps = listOf(
                    "confirm: app or folder path and that it is your own app or an authorized target",
                    "terminal_ubuntu: rea analyze <path>",
                    "terminal_ubuntu: read analysis output",
                    "speak: analysis summary"
                ),
                permissions = listOf("shell"), needsConfirmation = true
            )
        )
    )
)
