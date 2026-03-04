// SettingsStore.kt
package com.mende.dontspoilerfyme.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "settings")

private val KEY_CLAIMS_ORDER = stringPreferencesKey("claims_order")
private val KEY_CLAIMS_INDEX = intPreferencesKey("claims_index")
private val KEY_HAS_LAUNCHED_ONCE = booleanPreferencesKey("has_launched_once")

class SettingsStore(private val context: Context) {
    private val dataStore = context.dataStore
    private val keyLastAppOpenAdShown = longPreferencesKey("last_app_open_ad_shown_epoch_ms")
    private val keyOnboardingCompleted = booleanPreferencesKey("onboarding_completed")

    private object Keys {
        val SHIELD_ENABLED = booleanPreferencesKey("shield_enabled")

        val SHIELD_END_AT = longPreferencesKey("shield_end_at_epoch_ms")

        val DELAY_SECONDS = intPreferencesKey("delay_seconds")

        val SHIELD_DURATION_MINUTES = intPreferencesKey("shield_duration_minutes")

        // OLD (legacy): apps NOT delayed in old model
        val WHITELIST_PACKAGES = stringSetPreferencesKey("whitelist_packages")

        // NEW (Premium): packages to DELAY
        val DELAYED_PACKAGES = stringSetPreferencesKey("delayed_packages")

        val DELAY_MODE_MIGRATED_V1 = booleanPreferencesKey("delay_mode_migrated_v1")

        // Source of truth: billing writes here
        val IS_PREMIUM = booleanPreferencesKey("is_premium")
    }

    // ---- Helpers ----
    private fun encodeOrder(order: List<Int>): String = order.joinToString(",")

    private fun decodeOrder(s: String?): List<Int> =
        s?.split(",")
            ?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: emptyList()

    // ---- Flows ----
    val onboardingCompleted: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[keyOnboardingCompleted] ?: false }

    val lastAppOpenAdShownEpochMs: Flow<Long> =
        dataStore.data.map { it[keyLastAppOpenAdShown] ?: 0L }

    val shieldEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.SHIELD_ENABLED] ?: false }

    val shieldEndAtEpochMs: Flow<Long> =
        context.dataStore.data.map { it[Keys.SHIELD_END_AT] ?: 0L }

    val delaySeconds: Flow<Int> =
        context.dataStore.data.map { it[Keys.DELAY_SECONDS] ?: 30 }

    val shieldDurationMinutes: Flow<Int> =
        context.dataStore.data.map { it[Keys.SHIELD_DURATION_MINUTES] ?: 120 }

    // Legacy
    val whitelistPackages: Flow<Set<String>> =
        context.dataStore.data.map { it[Keys.WHITELIST_PACKAGES] ?: emptySet() }

    // Premium: packages selected for delay
    val delayedPackages: Flow<Set<String>> =
        context.dataStore.data.map { it[Keys.DELAYED_PACKAGES] ?: emptySet() }

    val isPremium: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.IS_PREMIUM] ?: false }

    // ---- Getters ----

    suspend fun getHasLaunchedOnce(): Boolean {
        return dataStore.data.first()[KEY_HAS_LAUNCHED_ONCE] ?: false
    }

    // ---- Setters ----
    suspend fun setOnboardingCompleted(value: Boolean) {
        dataStore.edit { it[keyOnboardingCompleted] = value }
    }

    suspend fun setLastAppOpenAdShownEpochMs(v: Long) {
        dataStore.edit { it[keyLastAppOpenAdShown] = v }
    }

    suspend fun setShieldEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHIELD_ENABLED] = enabled }
    }

    suspend fun setShieldEndAtEpochMs(endAt: Long) {
        context.dataStore.edit { it[Keys.SHIELD_END_AT] = endAt }
    }

    suspend fun setDelaySeconds(seconds: Int) {
        context.dataStore.edit { it[Keys.DELAY_SECONDS] = seconds.coerceIn(5, 3600) }
    }

    suspend fun setShieldDurationMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.SHIELD_DURATION_MINUTES] = minutes.coerceIn(5, 24 * 60) }
    }

    suspend fun setDelayedPackages(pkgs: Set<String>) {
        context.dataStore.edit { it[Keys.DELAYED_PACKAGES] = pkgs }
    }

    suspend fun setHasLaunchedOnce(value: Boolean) {
        dataStore.edit { it[KEY_HAS_LAUNCHED_ONCE] = value }
    }

    /**
     * One-time migration:
     * Old behavior (Premium): delay ALL apps except user whitelist.
     * New behavior (Premium): delay ONLY selected apps.
     *
     * Rules:
     * 0) If already migrated -> do nothing
     * 1) If DELAYED_PACKAGES key already exists (even if empty) -> do nothing (user choice or newer version)
     * 2) If old whitelist is empty -> do nothing (fresh install / never configured)
     * 3) Else migrate: delayed = launcherPkgs - ignoredPkgs - oldWhitelist
     */
    suspend fun migrateDelayModeIfNeeded(
        launcherPkgs: Set<String>,
        ignoredPkgs: Set<String>
    ) {
        val prefs = dataStore.data.first()

        val already = prefs[Keys.DELAY_MODE_MIGRATED_V1] ?: false
        if (already) return

        val hasDelayedKey = prefs.asMap().containsKey(Keys.DELAYED_PACKAGES)
        if (hasDelayedKey) {
            dataStore.edit { it[Keys.DELAY_MODE_MIGRATED_V1] = true }
            return
        }

        val oldWhitelist = prefs[Keys.WHITELIST_PACKAGES] ?: emptySet()
        if (oldWhitelist.isEmpty()) {
            dataStore.edit { it[Keys.DELAY_MODE_MIGRATED_V1] = true }
            return
        }

        val migrated = (launcherPkgs - ignoredPkgs - oldWhitelist).toSet()
        dataStore.edit {
            it[Keys.DELAYED_PACKAGES] = migrated
            it[Keys.DELAY_MODE_MIGRATED_V1] = true
        }
    }

    // ✅ Source of truth: Billing writes here
    suspend fun setPremiumFromBilling(isPremium: Boolean) {
        context.dataStore.edit { it[Keys.IS_PREMIUM] = isPremium }
    }

    suspend fun getNextHomeClaim(): String {
        val claims = com.mende.dontspoilerfyme.ui.HomeClaims.all
        val size = claims.size

        var order: List<Int>
        var index: Int

        val prefs = dataStore.data.first()
        order = decodeOrder(prefs[KEY_CLAIMS_ORDER])
        index = prefs[KEY_CLAIMS_INDEX] ?: 0

        val validOrder =
            order.size == size && order.toSet().size == size && order.all { it in 0 until size }
        if (!validOrder || index !in 0..size) {
            order = (0 until size).shuffled()
            index = 0
            dataStore.edit {
                it[KEY_CLAIMS_ORDER] = encodeOrder(order)
                it[KEY_CLAIMS_INDEX] = 0
            }
        }

        if (index >= size) {
            order = (0 until size).shuffled()
            index = 0
            dataStore.edit {
                it[KEY_CLAIMS_ORDER] = encodeOrder(order)
                it[KEY_CLAIMS_INDEX] = 0
            }
        }

        val claim = claims[order[index]]
        dataStore.edit { it[KEY_CLAIMS_INDEX] = index + 1 }
        return claim
    }
}
