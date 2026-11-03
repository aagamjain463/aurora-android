package app.aurora.protect

/*
 * Version 0 → 1: per-app preferences, groups, schedules and per-app Intentional App Opening become
 * presets, configurations and activations (docs/CONFIGURATIONS.md "Migration"). Pure and idempotent,
 * unit tested (ConfigMigrationTest). Nothing is deleted except the schedules list, whose every entry
 * becomes a recurring activation with the same id, days, times, mode and on/off state. The old per-app
 * settings stay in [Rules.apps], unread, so nothing is lost.
 *
 * What was enforced before is enforced after, and nothing more: saved preferences become saved presets
 * (no activation), schedules stay schedules, and Intent apps that were on stay on (an Always activation).
 * Running sessions, Focus, waits, grants and cooldowns live in Runtime and aren't touched.
 */
fun Rules.migratedToConfigurations(items: List<String>, now: Long): Rules {
    if (version >= 1) return this
    val shields = mutableListOf<ShieldPreset>()
    val configs = mutableListOf<UsageConfig>()
    val activations = mutableListOf<Activation>()
    val intents = mutableListOf<IntentPreset>()

    // Saved preferences: one preset per distinct setting, so each app keeps the mode it had.
    val byRule = items.groupBy { rule(it).let { r -> Triple(r.mode, r.friction, r.cooldownWaitMinutes) } }
    byRule.entries.forEachIndexed { i, (setting, keys) ->
        val (mode, friction, wait) = setting
        val name = if (byRule.size == 1) "Protected apps" else "Protected apps · ${mode.name.replace("AlwaysBlock", "Strict")}"
        shields += ShieldPreset("migrated-items-$i", name, keys, mode, friction, wait, now)
    }
    for (g in groups) shields += ShieldPreset("migrated-group-${g.id}", g.name, g.keys, defaultRule.mode, defaultRule.friction, defaultRule.cooldownWaitMinutes, now)

    for (s in schedules) {
        val keys = if (s.legacy) (s.groupId?.let { id -> groups.find { it.id == id }?.keys } ?: items)
        else s.keys + s.groupIds.flatMap { id -> groups.find { it.id == id }?.keys.orEmpty() }
        val preset = ShieldPreset("migrated-schedule-${s.id}", s.name, keys.distinct(), s.mode, s.friction, s.cooldownWaitMinutes, now)
        val length = ((s.endMinute - s.startMinute) % 1440 + 1440) % 1440
        shields += preset
        configs += UsageConfig("migrated-schedule-${s.id}", s.name, shieldId = preset.id, createdAt = now)
        // Same id as the schedule: a committed Strict window already running stays one session.
        activations += Activation(s.id, "migrated-schedule-${s.id}", Timing.Recurring(s.days, s.startMinute, if (length == 0) 1440 else length), s.enabled, createdAt = 0)
    }

    val withIntent = apps.filterValues { it.intent != null }.mapValues { it.value.intent!! }
    withIntent.entries.groupBy { (_, c) -> c.copy(enabled = true) }.entries.forEachIndexed { i, (config, entries) ->
        val keys = entries.map { it.key }
        val on = entries.filter { it.value.enabled }.map { it.key }
        val preset = IntentPreset("migrated-intent-$i", if (withIntent.size == 1) "Intentional opening" else "Intentional opening ${i + 1}", keys, config, now)
        intents += preset
        if (on.isNotEmpty()) {
            // Only the apps it was on for: the others stay saved in the preset, not active.
            val active = if (on.size == keys.size) preset else preset.copy(id = "${preset.id}-on", keys = on).also { intents += it }
            configs += UsageConfig("migrated-intent-$i", active.name, intentId = active.id, createdAt = now)
            activations += Activation("migrated-intent-$i", "migrated-intent-$i", Timing.Always, true, createdAt = now)
        }
    }

    return copy(
        version = 1,
        shields = this.shields + shields,
        intentPresets = this.intentPresets + intents,
        configs = this.configs + configs,
        activations = this.activations + activations,
        schedules = emptyList(),
    )
}

/**
 * Parts of apps used to be global switches ([Rules.content]): always on, "go back", turned off at any time.
 * They become one Flexible Shield over the same parts with an Always activation, which is that exact
 * strength, and from then on they're edited like any other Shield. Idempotent: it empties [Rules.content].
 */
fun Rules.migratedContent(now: Long): Rules {
    if (content.isEmpty()) return this
    val keys = content.flatMap { LegacyContent[it].orEmpty() }.distinct()
    if (keys.isEmpty()) return copy(content = emptySet())
    val id = "migrated-content-$now"
    val name = "Parts of apps"
    return copy(
        content = emptySet(),
        shields = shields + ShieldPreset(id, name, keys, Mode.Flexible, createdAt = now),
        configs = configs + UsageConfig(id, name, shieldId = id, createdAt = now),
        activations = activations + Activation(id, id, Timing.Always, true, createdAt = now),
    )
}
