package com.eldroid.facelock.ui.user

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.eldroid.facelock.R
import com.eldroid.facelock.data.repo.AuthRepository
import com.eldroid.facelock.data.repo.FirebaseRefs
import com.eldroid.facelock.data.repo.UserRepository
import com.eldroid.facelock.databinding.ActivityFaceEnrollBinding
import com.eldroid.facelock.util.visible
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream

/**
 * Captures the user's face, validates that exactly one clear face is present
 * using on-device ML Kit, then uploads the image to Cloud Storage where the
 * backend recognition service generates and stores the face template.
 *
 * Every outcome of that flow is a screen state rather than a Toast: a toast
 * over a live camera preview is unreadable, disappears before it can be acted
 * on, and leaves no way to retry. [Step] enumerates the states; [render] is the
 * only place that decides what is on screen.
 */
class FaceEnrollActivity : AppCompatActivity() {

    /** What the user is currently looking at. Exactly one layer is visible. */
    private enum class Step { SCANNING, PROCESSING, RESULT, PERMISSION }

    private lateinit var binding: ActivityFaceEnrollBinding
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private val authRepo = AuthRepository()
    private val userRepo = UserRepository()

    private var step = Step.SCANNING

    /** True once the framing analyser sees exactly one big-enough face. */
    private var faceReady = false

    /** The live analyser never ran, so don't gate the shutter on its verdict. */
    private var analyzerRunning = false

    /** Accurate but slow: used once, on the captured still. */
    private val stillDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.25f)
                .build()
        )
    }

    /** Fast and landmark-free: runs on every preview frame, so it must be cheap. */
    private val streamDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.15f)
                .build()
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else showPermissionStep()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFaceEnrollBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnCapture.setOnClickListener { capture() }
        binding.btnPermCancel.setOnClickListener { finish() }

        setGuidance(R.string.scan_starting, ready = false)
        render(Step.SCANNING)

        if (hasCameraPermission()) startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    /** Coming back from Settings is the usual way permission gets granted. */
    override fun onResume() {
        super.onResume()
        if (step == Step.PERMISSION && hasCameraPermission()) startCamera()
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------ camera ----

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = try {
                providerFuture.get()
            } catch (e: Exception) {
                showCameraError()
                return@addListener
            }
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            // Framing feedback. Dropping stale frames matters more than seeing
            // every one, so the analyser only ever holds the newest.
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(ContextCompat.getMainExecutor(this), ::analyzeFrame) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageCapture, analysis
                )
                setGuidance(R.string.scan_no_face, ready = false)
                render(Step.SCANNING)
            } catch (e: Exception) {
                showCameraError()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Turns each preview frame into one line of advice. Only the face count and
     * how much of the frame the face fills are used — enough to stop the two
     * failures that actually happen (nobody in frame, or too far away) without
     * making the hint jitter.
     */
    @androidx.camera.core.ExperimentalGetImage
    private fun analyzeFrame(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        analyzerRunning = true
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)

        streamDetector.process(input)
            .addOnSuccessListener { faces ->
                if (step != Step.SCANNING) return@addOnSuccessListener
                when {
                    faces.isEmpty() -> setGuidance(R.string.scan_no_face, ready = false)
                    faces.size > 1 -> setGuidance(R.string.scan_many_faces, ready = false)
                    else -> {
                        val frameWidth =
                            if (proxy.imageInfo.rotationDegrees % 180 == 0) proxy.width
                            else proxy.height
                        val fill = faces[0].boundingBox.width().toFloat() / frameWidth
                        if (fill < MIN_FACE_FILL) setGuidance(R.string.scan_too_far, ready = false)
                        else setGuidance(R.string.scan_ready, ready = true)
                    }
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    /** Single place that moves the framing hint, the oval and the shutter. */
    private fun setGuidance(@StringRes message: Int, ready: Boolean) {
        faceReady = ready
        binding.tvGuidance.setText(message)
        binding.ovalFrame.setBackgroundResource(
            if (ready) R.drawable.bg_focus_frame_ready else R.drawable.bg_focus_frame
        )
        binding.ivGuidance.setColorFilter(
            getColor(if (ready) R.color.accent else R.color.text_on_dark_muted)
        )
        // Gate the shutter on a usable frame, but never trap the user: if the
        // analyser never produced a verdict, leave it tappable.
        val enabled = ready || !analyzerRunning
        binding.btnCapture.isEnabled = enabled
        binding.btnCapture.alpha = if (enabled) 1f else 0.5f
    }

    // ----------------------------------------------------------- capture ----

    private fun capture() {
        val capture = imageCapture ?: return
        showProcessing(R.string.enroll_checking)

        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(proxy: ImageProxy) {
                    val bitmap = proxy.toBitmap()
                    val rotation = proxy.imageInfo.rotationDegrees
                    proxy.close()
                    validateAndUpload(bitmap, rotation)
                }

                override fun onError(exception: ImageCaptureException) {
                    showFailure(
                        R.string.enroll_failed_title,
                        getString(R.string.enroll_capture_failed_body)
                    )
                }
            }
        )
    }

    private fun validateAndUpload(bitmap: android.graphics.Bitmap, rotation: Int) {
        lifecycleScope.launch {
            try {
                val input = InputImage.fromBitmap(bitmap, rotation)
                val faces = stillDetector.process(input).await()

                when {
                    faces.isEmpty() -> {
                        showFailure(
                            R.string.enroll_no_face_title,
                            getString(R.string.enroll_no_face_body)
                        )
                        return@launch
                    }
                    faces.size > 1 -> {
                        showFailure(
                            R.string.enroll_many_faces_title,
                            getString(R.string.enroll_many_faces_body)
                        )
                        return@launch
                    }
                }

                showProcessing(R.string.enroll_uploading)

                val uid = authRepo.currentUid ?: error("Not signed in.")
                val bytes = ByteArrayOutputStream().apply {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, this)
                }.toByteArray()

                // The recognition backend reads this path, extracts the embedding,
                // and writes it to face_templates/{uid}. Raw images are purged after.
                FirebaseRefs.storage.reference
                    .child("${FirebaseRefs.FACE_TEMPLATES}/$uid/enroll.jpg")
                    .putBytes(bytes)
                    .await()

                userRepo.markFaceEnrolled(uid, true)
                showSuccess()
            } catch (e: Exception) {
                showFailure(
                    R.string.enroll_failed_title,
                    e.message ?: getString(R.string.enroll_upload_failed_body)
                )
            }
        }
    }

    // ------------------------------------------------------------ states ----

    private fun showProcessing(@StringRes message: Int) {
        binding.tvProcessing.setText(message)
        render(Step.PROCESSING)
        announce(getString(message))
    }

    private fun showSuccess() {
        binding.resultIconWrap.setBackgroundResource(R.drawable.pill_granted)
        binding.ivResult.setImageResource(R.drawable.ic_check)
        binding.ivResult.setColorFilter(getColor(R.color.granted))
        binding.tvResultTitle.setText(R.string.enroll_success_title)
        binding.tvResultBody.setText(R.string.enroll_success_body)

        binding.btnResultPrimary.setText(R.string.enroll_done)
        binding.btnResultPrimary.setOnClickListener { finish() }
        binding.btnResultSecondary.visible(false)

        render(Step.RESULT)
        announce("${getString(R.string.enroll_success_title)}. ${getString(R.string.enroll_success_body)}")
    }

    private fun showFailure(@StringRes title: Int, body: String) {
        binding.resultIconWrap.setBackgroundResource(R.drawable.pill_denied)
        binding.ivResult.setImageResource(R.drawable.ic_alert)
        binding.ivResult.setColorFilter(getColor(R.color.denied))
        binding.tvResultTitle.setText(title)
        binding.tvResultBody.text = body

        binding.btnResultPrimary.setText(R.string.enroll_retry)
        binding.btnResultPrimary.setOnClickListener {
            setGuidance(R.string.scan_no_face, ready = false)
            render(Step.SCANNING)
        }
        binding.btnResultSecondary.visible(true)
        binding.btnResultSecondary.setText(R.string.action_cancel)
        binding.btnResultSecondary.setOnClickListener { finish() }

        render(Step.RESULT)
        announce("${getString(title)}. $body")
    }

    /**
     * Denied once, or denied permanently? The first can be re-asked in place;
     * the second can only be undone in Settings, so the button has to say so.
     */
    private fun showPermissionStep() {
        val canAskAgain =
            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)

        binding.ivPermission.setImageResource(R.drawable.ic_camera)
        binding.ivPermission.setColorFilter(getColor(R.color.warning))
        binding.tvPermTitle.setText(if (canAskAgain) R.string.perm_title else R.string.perm_blocked_title)
        binding.tvPermBody.setText(if (canAskAgain) R.string.perm_body else R.string.perm_blocked_body)
        binding.btnGrant.setText(if (canAskAgain) R.string.perm_allow else R.string.perm_open_settings)
        binding.btnGrant.setOnClickListener {
            if (canAskAgain) permissionLauncher.launch(Manifest.permission.CAMERA)
            else openAppSettings()
        }
        binding.btnPermCancel.setText(R.string.not_now)

        render(Step.PERMISSION)
        announce(binding.tvPermTitle.text.toString())
    }

    private fun showCameraError() {
        binding.ivPermission.setImageResource(R.drawable.ic_alert)
        binding.ivPermission.setColorFilter(getColor(R.color.denied))
        binding.tvPermTitle.setText(R.string.camera_error_title)
        binding.tvPermBody.setText(R.string.camera_error_body)
        binding.btnGrant.setText(R.string.camera_retry)
        binding.btnGrant.setOnClickListener { startCamera() }
        binding.btnPermCancel.setText(R.string.action_cancel)

        render(Step.PERMISSION)
        announce(getString(R.string.camera_error_title))
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
    }

    private fun render(next: Step) {
        step = next
        binding.groupScanning.visible(next == Step.SCANNING)
        binding.groupProcessing.visible(next == Step.PROCESSING)
        binding.groupResult.visible(next == Step.RESULT)
        binding.groupPermission.visible(next == Step.PERMISSION)

        // The result and permission layers own the whole screen; closing from
        // there is the buttons' job, so the toolbar would only be noise.
        val overCamera = next == Step.SCANNING || next == Step.PROCESSING
        binding.toolbar.visible(overCamera)
        binding.scrimTop.visible(overCamera)
    }

    /** Outcomes must reach TalkBack, not just the screen. */
    private fun announce(message: String) {
        binding.root.announceForAccessibility(message)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        stillDetector.close()
        streamDetector.close()
    }

    private companion object {
        /** Face must fill this share of the frame's width to be close enough. */
        const val MIN_FACE_FILL = 0.26f
    }
}
