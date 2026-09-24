package com.eldroid.facelock.ui.admin

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.eldroid.facelock.R
import com.eldroid.facelock.data.repo.FirebaseRefs
import com.eldroid.facelock.data.repo.RealtimeDbRepository
import com.eldroid.facelock.databinding.ActivityLedControlBinding
import com.eldroid.facelock.util.asRelativeDateTime
import com.eldroid.facelock.util.snack
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * Switches an LED on the ESP32 from the phone.
 *
 * Nothing here talks to the device: the phone writes a single boolean to the
 * Realtime Database and the sketch, which keeps a listener on the same key,
 * drives its GPIO from it. That indirection is what keeps this on the free
 * plan — no Cloud Function, and no need for the two to be on one network.
 *
 * Because the database is the state, the screen never assumes a tap worked. The
 * lamp only changes when the value comes back from Firebase, so what is on
 * screen is what the device is actually being told.
 */
class LedControlActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLedControlBinding
    private val rtdb = RealtimeDbRepository()

    /** Last value read back. Null while the key does not exist yet. */
    private var isOn: Boolean? = null

    /** Latest `.info/connected`, used to explain a write that cannot land yet. */
    private var connected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLedControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.tvPath.text = FirebaseRefs.RTDB_LED_PATH

        // An unset key is treated as off, so the first tap turns the light on.
        binding.btnToggle.setOnClickListener { write(isOn != true) }
        binding.btnOn.setOnClickListener { write(true) }
        binding.btnOff.setOnClickListener { write(false) }

        render(null)
        observeValue()
        observeConnection()
    }

    private fun observeValue() {
        lifecycleScope.launch {
            rtdb.observeBool(FirebaseRefs.RTDB_LED_PATH)
                .catch { e ->
                    binding.tvRawValue.setText(R.string.led_value_unreadable)
                    binding.tvUpdated.setText(R.string.device_test_read_failed)
                    binding.root.snack(e.message ?: getString(R.string.device_test_read_failed))
                }
                .collect { value ->
                    isOn = value
                    render(value)
                    binding.tvUpdated.text = getString(
                        R.string.device_test_updated,
                        System.currentTimeMillis().asRelativeDateTime()
                    )
                }
        }
    }

    /** Paints the lamp, the word under it and the raw value from one source. */
    private fun render(value: Boolean?) {
        val on = value == true

        binding.lamp.setBackgroundResource(
            if (on) R.drawable.bg_led_on else R.drawable.bg_led_off
        )
        binding.ivBulb.imageTintList = getColorStateList(
            if (on) R.color.ink_900 else R.color.text_on_dark_muted
        )
        binding.tvState.setText(
            when (value) {
                true -> R.string.led_state_on
                false -> R.string.led_state_off
                null -> R.string.led_state_unset
            }
        )
        binding.btnToggle.setText(if (on) R.string.led_turn_off else R.string.led_turn_on)

        // The raw text is deliberately the JSON spelling, not the friendly word:
        // it is there to be compared against the Firebase console. Offline it is
        // the local cache rather than the server, and the two can differ, so it
        // is marked as unconfirmed instead of being shown as fact.
        val raw = value?.toString() ?: getString(R.string.led_value_absent)
        binding.tvRawValue.text =
            if (connected) raw else getString(R.string.led_value_unconfirmed, raw)
    }

    private fun observeConnection() {
        lifecycleScope.launch {
            rtdb.observeConnected().collect { connected ->
                this@LedControlActivity.connected = connected
                // Whether the shown value is trustworthy depends on this, so the
                // card has to be repainted when it changes.
                render(isOn)
                binding.tvConnection.setText(
                    if (connected) R.string.device_test_connected
                    else R.string.device_test_offline
                )
                binding.tvConnection.setBackgroundResource(
                    if (connected) R.drawable.pill_granted else R.drawable.pill_denied
                )
                binding.tvConnection.setTextColor(
                    getColor(if (connected) R.color.granted else R.color.denied)
                )
            }
        }
    }

    /**
     * The buttons are deliberately never disabled while a write is in flight.
     *
     * A Realtime Database write only completes when the server acknowledges it,
     * and offline there is no acknowledgement — the SDK queues the write and
     * flushes it whenever the socket comes back, which may be minutes later or
     * never. Waiting on that to re-enable the controls locks the screen on the
     * first offline tap. The database is the state and the listener is the only
     * thing that paints it, so the tap just fires and the listener catches up.
     */
    private fun write(value: Boolean) {
        // Offline the value still applies to the local cache, so the lamp will
        // flip and read right while Firebase has not actually been told. Say so,
        // rather than letting the screen imply the device got the message.
        if (!connected) binding.root.snack(getString(R.string.led_queued_offline))

        lifecycleScope.launch {
            rtdb.setBool(FirebaseRefs.RTDB_LED_PATH, value)
                .onSuccess {
                    binding.root.snack(
                        getString(if (value) R.string.led_sent_on else R.string.led_sent_off)
                    )
                }
                .onFailure {
                    binding.root.snack(
                        it.message ?: getString(R.string.device_test_write_failed)
                    )
                }
        }
    }
}
