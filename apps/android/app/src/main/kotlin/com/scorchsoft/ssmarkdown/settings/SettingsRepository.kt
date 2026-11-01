package com.scorchsoft.ssmarkdown.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The one place settings are read and changed. Screens observe [settings]
 * and change it with [update]; the implementation persists each change.
 *
 * An interface so view-model tests use [InMemorySettingsRepository] and
 * never touch `SharedPreferences`.
 */
interface SettingsRepository {
    /** The current settings; never stale, never null. */
    val settings: StateFlow<AppSettings>

    /** Applies [change] and persists the result. */
    fun update(change: (AppSettings) -> AppSettings)
}

/** Settings held in memory only: tests, previews and the screenshot harness. */
class InMemorySettingsRepository(
    initial: AppSettings = AppSettings(),
) : SettingsRepository {
    private val state = MutableStateFlow(initial)

    override val settings: StateFlow<AppSettings> = state.asStateFlow()

    override fun update(change: (AppSettings) -> AppSettings) = state.update(change)
}

/**
 * Settings persisted through [store] after every change. Reading happens
 * once, at construction, off whatever thread builds the graph; a store
 * that fails to read yields the defaults rather than a crash (nothing on
 * the launch path may throw).
 */
class PersistedSettingsRepository(
    private val store: SettingsStore,
) : SettingsRepository {
    private val state = MutableStateFlow(store.read())

    override val settings: StateFlow<AppSettings> = state.asStateFlow()

    override fun update(change: (AppSettings) -> AppSettings) {
        state.update(change)
        store.write(state.value)
    }
}

/** Reads and writes [AppSettings] somewhere durable. */
interface SettingsStore {
    /** The stored settings, or the defaults if nothing (readable) is stored. */
    fun read(): AppSettings

    /** Stores [settings], replacing what was there. */
    fun write(settings: AppSettings)
}
