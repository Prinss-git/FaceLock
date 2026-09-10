package com.eldroid.facelock.ui.admin

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.eldroid.facelock.R
import com.eldroid.facelock.data.repo.FirebaseRefs
import com.eldroid.facelock.data.repo.RealtimeDbRepository
import com.eldroid.facelock.databinding.ActivityDeviceTestBinding
import com.eldroid.facelock.util.asRelativeDateTime
import com.eldroid.facelock.util.snack
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Two-way link check between the phone and the ESP32.
 *
 * Both ends talk to the same Realtime Database key: the device writes it with
 * its database secret, the phone reads and writes it as a signed-in user. If
 * the numeral here tracks what the sketch prints, the whole chain works — and
 * it needs no Cloud Functions, so it runs on the free plan.
 */
class DeviceTestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceTestBinding
    private val rtdb = RealtimeDbRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnWrite.setOnClickListener { writeTypedValue() }
        binding.btnZero.setOnClickListener { write(0) }
        binding.btnHundred.setOnClickListener { write(100) }
        binding.btnRandom.setOnClickListener { write(Random.nextInt(1, 1000)) }

        binding.etValue.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { writeTypedValue(); true } else false
        }

        observeValue()
        observeConnection()
    }

    private fun observeValue() {
        lifecycleScope.launch {
            rtdb.observeInt(FirebaseRefs.RTDB_TEST_PATH)
                .catch { e ->
                    binding.tvValue.text = "—"
                    binding.tvUpdated.setText(R.string.device_test_read_failed)
                    binding.root.snack(e.message ?: getString(R.string.device_test_read_failed))
                }
                .collect { value ->
                    binding.tvValue.text = value?.toString()
                        ?: getString(R.string.device_test_no_value)
                    binding.tvUpdated.text = getString(
                        R.string.device_test_updated,
                        System.currentTimeMillis().asRelativeDateTime()
                    )
                }
        }
    }

    private fun observeConnection() {
        lifecycleScope.launch {
            rtdb.observeConnected().collect { connected ->
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

    private fun writeTypedValue() {
        val typed = binding.etValue.text.toString().trim().toIntOrNull()
        if (typed == null) {
            binding.tilValue.error = getString(R.string.device_test_bad_number)
            return
        }
        binding.tilValue.error = null
        write(typed)
    }

    private fun write(value: Int) {
        binding.btnWrite.isEnabled = false
        lifecycleScope.launch {
            rtdb.setInt(FirebaseRefs.RTDB_TEST_PATH, value)
                .onSuccess { binding.root.snack(getString(R.string.device_test_wrote, value)) }
                .onFailure { binding.root.snack(it.message ?: getString(R.string.device_test_write_failed)) }
            binding.btnWrite.isEnabled = true
        }
    }
}
